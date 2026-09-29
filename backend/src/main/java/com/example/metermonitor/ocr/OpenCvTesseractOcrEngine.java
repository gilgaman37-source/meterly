package com.example.metermonitor.ocr;

import com.example.metermonitor.common.ApiException;
import com.example.metermonitor.config.AppProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import nu.pattern.OpenCV;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ocr.engine", havingValue = "tesseract", matchIfMissing = true)
public class OpenCvTesseractOcrEngine implements OcrEngine {
    private static final Pattern DECIMAL = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private final Object tesseract;

    public OpenCvTesseractOcrEngine(AppProperties properties) {
        OpenCV.loadLocally();
        try {
            Class<?> type = Class.forName("net.sourceforge.tess4j.Tesseract");
            tesseract = type.getConstructor().newInstance();
            if (properties.ocr().tessdataPath() != null && !properties.ocr().tessdataPath().isBlank()) {
                type.getMethod("setDatapath", String.class).invoke(tesseract, properties.ocr().tessdataPath());
            }
            type.getMethod("setLanguage", String.class).invoke(tesseract, "eng");
            type.getMethod("setPageSegMode", int.class).invoke(tesseract, 7);
            type.getMethod("setVariable", String.class, String.class)
                    .invoke(tesseract, "tessedit_char_whitelist", "0123456789.,");
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Tess4J is not available", ex);
        }
    }

    @Override
    public Result recognize(Path image) {
        Mat original = readImage(image);
        Mat gray = new Mat();
        Mat denoised = new Mat();
        Mat binary = new Mat();
        Mat edges = new Mat();
        try {
            Imgproc.cvtColor(original, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(gray, denoised, new Size(5, 5), 0);
            Imgproc.threshold(denoised, binary, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
            Imgproc.Canny(binary, edges, 50, 150);

            Attempt mechanical = recognizeMechanicalCounter(original, gray);
            if (mechanical != null) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("displayRegionDetected", true);
                metadata.put("cropX", mechanical.candidate().rect().x);
                metadata.put("cropY", mechanical.candidate().rect().y);
                metadata.put("cropWidth", mechanical.candidate().rect().width);
                metadata.put("cropHeight", mechanical.candidate().rect().height);
                metadata.put("redFractionDigits", mechanical.redFractionDigits());
                metadata.put("recognitionMode", "mechanical-digits");
                return new Result(mechanical.value(), confidence(mechanical.confidence()), mechanical.raw(), metadata);
            }

            List<Candidate> candidates = displayCandidates(original, gray, edges);
            Attempt best = null;
            for (Candidate candidate : candidates) {
                Mat colorCrop = new Mat(original, candidate.rect());
                Mat grayCrop = new Mat(gray, candidate.rect());
                try {
                    int redFractionDigits = countRedDigitGroups(colorCrop);
                    List<Mat> variants = normalizedVariants(grayCrop);
                    try {
                        for (Mat variant : variants) {
                            Attempt attempt = runOcr(variant, candidate, redFractionDigits);
                            if (attempt != null && (best == null || attempt.score() > best.score())) {
                                best = attempt;
                            }
                        }
                    } finally {
                        variants.forEach(Mat::release);
                    }
                } finally {
                    colorCrop.release();
                    grayCrop.release();
                }
            }

            if (best == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "На изображении не найдено распознаваемое числовое показание");
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("displayRegionDetected", best.candidate().detected());
            metadata.put("cropX", best.candidate().rect().x);
            metadata.put("cropY", best.candidate().rect().y);
            metadata.put("cropWidth", best.candidate().rect().width);
            metadata.put("cropHeight", best.candidate().rect().height);
            metadata.put("redFractionDigits", best.redFractionDigits());
            return new Result(best.value(), confidence(best.confidence()), best.raw(), metadata);
        } finally {
            original.release();
            gray.release();
            denoised.release();
            binary.release();
            edges.release();
        }
    }

    private Mat readImage(Path image) {
        try {
            byte[] bytes = Files.readAllBytes(image);
            MatOfByte encoded = new MatOfByte(bytes);
            try {
                Mat decoded = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
                if (!decoded.empty()) return decoded;
                decoded.release();
            } finally {
                encoded.release();
            }
        } catch (IOException ignored) {
            // The API deliberately returns the same safe validation message for unreadable input.
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "Не удалось прочитать изображение");
    }

    private Attempt recognizeMechanicalCounter(Mat color, Mat gray) {
        MechanicalLayout layout = findMechanicalLayout(color);
        if (layout == null) return null;
        StringBuilder digits = new StringBuilder();
        double quality = 0;
        for (int index = 0; index < layout.totalDigits(); index++) {
            double centerX = layout.firstCenterX() + index * layout.pitch();
            int x = Math.max(0, (int) Math.round(centerX - layout.pitch() * .44));
            int right = Math.min(gray.cols(), (int) Math.round(centerX + layout.pitch() * .44));
            if (right - x < 8) return null;
            Rect cellRect = new Rect(x, layout.rect().y, right - x, layout.rect().height);
            Mat grayCell = new Mat(gray, cellRect);
            Mat colorCell = new Mat(color, cellRect);
            try {
                boolean fractional = index >= layout.totalDigits() - layout.fractionDigits();
                ClassifiedDigit digit = classifyMechanicalDigit(colorCell, grayCell, fractional);
                if (digit == null) return null;
                digits.append(digit.value());
                quality += digit.quality();
            } finally {
                grayCell.release();
                colorCell.release();
            }
        }
        if (digits.length() <= layout.fractionDigits()) return null;
        int decimalAt = digits.length() - layout.fractionDigits();
        String decimal = digits.substring(0, decimalAt) + "." + digits.substring(decimalAt);
        BigDecimal value;
        try {
            value = new BigDecimal(decimal);
        } catch (NumberFormatException ex) {
            return null;
        }
        double averageQuality = quality / layout.totalDigits();
        if (averageQuality < .58) return null;
        Candidate candidate = new Candidate(layout.rect(), 12, true);
        double ocrConfidence = Math.min(.94, .80 + averageQuality * .14);
        return new Attempt(value, ocrConfidence, decimal, candidate, layout.fractionDigits(), 100);
    }

    private MechanicalLayout findMechanicalLayout(Mat color) {
        Mat red = redMask(color);
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            Imgproc.findContours(red, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            List<Rect> glyphs = contours.stream().map(Imgproc::boundingRect)
                    .filter(rect -> rect.height >= Math.max(9, color.rows() * .025))
                    .filter(rect -> rect.height <= color.rows() * .13)
                    .filter(rect -> rect.width >= 3 && rect.width <= color.cols() * .08)
                    .filter(rect -> rect.y + rect.height / 2.0 < color.rows() * .55)
                    .sorted(Comparator.comparingInt(rect -> rect.y + rect.height / 2))
                    .toList();
            List<Rect> best = List.of();
            double bestCenterY = Double.POSITIVE_INFINITY;
            int yTolerance = Math.max(10, color.rows() / 30);
            for (Rect seed : glyphs) {
                double seedCenter = seed.y + seed.height / 2.0;
                List<Rect> row = glyphs.stream()
                        .filter(rect -> Math.abs(rect.y + rect.height / 2.0 - seedCenter) <= yTolerance)
                        .sorted(Comparator.comparingInt(rect -> rect.x))
                        .toList();
                if (row.size() < 3 || row.size() > 7) continue;
                List<Rect> regular = mostRegularRun(row);
                if (regular.size() < 3 || regular.size() > 4) continue;
                double centerY = regular.stream().mapToDouble(rect -> rect.y + rect.height / 2.0).average().orElse(999);
                if (centerY < bestCenterY) {
                    bestCenterY = centerY;
                    best = regular;
                }
            }
            if (best.size() < 3) return null;

            List<Double> centers = best.stream().map(rect -> rect.x + rect.width / 2.0).sorted().toList();
            List<Double> gaps = new ArrayList<>();
            for (int i = 1; i < centers.size(); i++) gaps.add(centers.get(i) - centers.get(i - 1));
            gaps.sort(Double::compareTo);
            double measuredPitch = gaps.get(gaps.size() / 2);
            if (measuredPitch < color.cols() * .025 || measuredPitch > color.cols() * .09) return null;
            for (double gap : gaps) if (Math.abs(gap - measuredPitch) > measuredPitch * .28) return null;
            double pitch = measuredPitch * .98;

            int fractionDigits = best.size();
            int integerDigits = 5;
            int totalDigits = integerDigits + fractionDigits;
            double firstRedCenter = centers.get(0);
            // Glyph bounds are slightly narrower than the drum pitch and 9 is left-heavy.
            double firstCenter = firstRedCenter - integerDigits * pitch;
            int minY = best.stream().mapToInt(rect -> rect.y).min().orElse(0);
            int maxY = best.stream().mapToInt(rect -> rect.y + rect.height).max().orElse(color.rows());
            int digitHeight = maxY - minY;
            int y = Math.max(0, minY - Math.max(3, (int) Math.round(digitHeight * .15)));
            int bottom = Math.min(color.rows(), y + Math.max(24, (int) Math.round(pitch * 1.05)));
            int left = Math.max(0, (int) Math.round(firstCenter - pitch * .52));
            int right = Math.min(color.cols(), (int) Math.round(centers.get(centers.size() - 1) + pitch * .52));
            if (firstCenter < 0 || right - left < pitch * (totalDigits - .5) || bottom - y < 20) return null;
            return new MechanicalLayout(new Rect(left, y, right - left, bottom - y), firstCenter, pitch,
                    totalDigits, fractionDigits);
        } finally {
            red.release();
            hierarchy.release();
            contours.forEach(Mat::release);
        }
    }

    private List<Rect> mostRegularRun(List<Rect> row) {
        List<Rect> best = List.of();
        for (int start = 0; start < row.size(); start++) {
            for (int end = start + 3; end <= Math.min(row.size(), start + 4); end++) {
                List<Rect> run = row.subList(start, end);
                double span = run.get(run.size() - 1).x + run.get(run.size() - 1).width / 2.0
                        - run.get(0).x - run.get(0).width / 2.0;
                double pitch = span / (run.size() - 1);
                if (pitch <= 0) continue;
                boolean regular = true;
                for (int i = 1; i < run.size(); i++) {
                    double previous = run.get(i - 1).x + run.get(i - 1).width / 2.0;
                    double current = run.get(i).x + run.get(i).width / 2.0;
                    if (Math.abs(current - previous - pitch) > pitch * .28) regular = false;
                }
                if (regular && run.size() > best.size()) best = List.copyOf(run);
            }
        }
        return best;
    }

    private ClassifiedDigit classifyMechanicalDigit(Mat colorCell, Mat grayCell, boolean redDigit) {
        Mat binary = new Mat();
        Mat foreground = new Mat();
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        try {
            if (redDigit) {
                Mat mask = redMask(colorCell);
                mask.copyTo(foreground);
                mask.release();
            } else {
                Integer tesseractDigit = recognizeSingleDigit(grayCell);
                if (tesseractDigit != null) return new ClassifiedDigit(tesseractDigit, .94);
                int blockSize = Math.min(11, Math.min(grayCell.rows(), grayCell.cols()));
                if (blockSize % 2 == 0) blockSize--;
                if (blockSize < 3) return null;
                Imgproc.adaptiveThreshold(grayCell, binary, 255, Imgproc.ADAPTIVE_THRESH_MEAN_C,
                        Imgproc.THRESH_BINARY, blockSize, 8);
                Core.bitwise_not(binary, foreground);
            }
            Mat closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2));
            try {
                Imgproc.morphologyEx(foreground, foreground, Imgproc.MORPH_CLOSE, closeKernel);
            } finally {
                closeKernel.release();
            }
            int labelsCount = Imgproc.connectedComponentsWithStats(foreground, labels, stats, centroids);
            int bestLabel = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int label = 1; label < labelsCount; label++) {
                int x = (int) stats.get(label, Imgproc.CC_STAT_LEFT)[0];
                int y = (int) stats.get(label, Imgproc.CC_STAT_TOP)[0];
                int width = (int) stats.get(label, Imgproc.CC_STAT_WIDTH)[0];
                int height = (int) stats.get(label, Imgproc.CC_STAT_HEIGHT)[0];
                int area = (int) stats.get(label, Imgproc.CC_STAT_AREA)[0];
                if (width < grayCell.cols() * .20 || height < grayCell.rows() * .28 || area < 15) continue;
                if (width > grayCell.cols() * .94 && height < grayCell.rows() * .25) continue;
                double centerY = y + height / 2.0;
                double score = area * 2.0 + height * 5.0
                        - Math.abs(centerY - grayCell.rows() * .60) * 7.0;
                if (score > bestScore) {
                    bestScore = score;
                    bestLabel = label;
                }
            }
            if (bestLabel < 0) return null;
            Mat glyph = new Mat();
            Core.compare(labels, new Scalar(bestLabel), glyph, Core.CMP_EQ);
            try {
                return classifyGlyph(glyph);
            } finally {
                glyph.release();
            }
        } finally {
            binary.release();
            foreground.release();
            labels.release();
            stats.release();
            centroids.release();
        }
    }

    private Integer recognizeSingleDigit(Mat grayCell) {
        Mat enlarged = new Mat();
        Mat bordered = new Mat();
        MatOfByte encoded = new MatOfByte();
        try {
            Imgproc.resize(grayCell, enlarged, new Size(210, 320), 0, 0, Imgproc.INTER_CUBIC);
            Core.copyMakeBorder(enlarged, bordered, 12, 12, 12, 12, Core.BORDER_CONSTANT, new Scalar(255));
            Imgcodecs.imencode(".png", bordered, encoded);
            BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(encoded.toArray()));
            if (buffered == null) return null;
            String raw;
            synchronized (tesseract) {
                tesseract.getClass().getMethod("setPageSegMode", int.class).invoke(tesseract, 10);
                raw = ((String) tesseract.getClass().getMethod("doOCR", BufferedImage.class)
                        .invoke(tesseract, buffered)).replaceAll("\\s+", "");
                tesseract.getClass().getMethod("setPageSegMode", int.class).invoke(tesseract, 7);
            }
            return raw.matches("\\d") ? raw.charAt(0) - '0' : null;
        } catch (ReflectiveOperationException | IOException ex) {
            return null;
        } finally {
            enlarged.release();
            bordered.release();
            encoded.release();
        }
    }

    private ClassifiedDigit classifyGlyph(Mat glyph) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Mat contourInput = glyph.clone();
        try {
            Imgproc.findContours(contourInput, contours, hierarchy, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE);
            if (contours.isEmpty()) return null;
            Rect bounds = contours.stream().map(Imgproc::boundingRect)
                    .max(Comparator.comparingDouble(Rect::area)).orElse(null);
            if (bounds == null || bounds.width < 2 || bounds.height < 4) return null;
            int holes = 0;
            double holeCenterY = .5;
            double largestHole = 0;
            for (int i = 0; i < contours.size(); i++) {
                double[] relation = hierarchy.empty() ? null : hierarchy.get(0, i);
                if (relation == null || relation[3] < 0) continue;
                double area = Imgproc.contourArea(contours.get(i));
                if (area < bounds.area() * .012) continue;
                holes++;
                if (area > largestHole) {
                    largestHole = area;
                    Rect hole = Imgproc.boundingRect(contours.get(i));
                    holeCenterY = (hole.y + hole.height / 2.0 - bounds.y) / bounds.height;
                }
            }
            double aspect = bounds.width / (double) bounds.height;
            double topMiddle = density(glyph, bounds, .25, 0, .75, .22);
            double upperLeft = density(glyph, bounds, 0, .15, .34, .48);
            double upperRight = density(glyph, bounds, .66, .15, 1, .48);
            double lowerLeft = density(glyph, bounds, 0, .52, .34, .86);
            double lowerRight = density(glyph, bounds, .66, .52, 1, .88);
            double bottomMiddle = density(glyph, bounds, .22, .78, .78, 1);
            if (holes >= 2) return new ClassifiedDigit(8, .94);
            if (holes == 1) {
                if (holeCenterY < .43) {
                    boolean lowerLoop = Math.abs(lowerLeft - lowerRight) < .16 && lowerLeft > .55;
                    if (lowerLoop || (bottomMiddle > .85 && lowerLeft > .70)) return new ClassifiedDigit(8, .90);
                    return new ClassifiedDigit(9, .88);
                }
                if (holeCenterY > .58) return new ClassifiedDigit(6, .88);
                return new ClassifiedDigit(0, .93);
            }

            if (aspect < .32) return new ClassifiedDigit(1, .86);
            if (bottomMiddle > .90 && lowerLeft > .55 && topMiddle < .80) return new ClassifiedDigit(9, .82);
            if (upperLeft - upperRight > .20) return new ClassifiedDigit(5, .84);
            if (upperRight > .35 && lowerLeft > .28 && lowerRight < .34) return new ClassifiedDigit(2, .78);
            if (topMiddle > .30 && upperRight > .30 && lowerRight > .30 && lowerLeft < .25) {
                return new ClassifiedDigit(bottomMiddle > .18 ? 3 : 7, .80);
            }
            if (upperRight > upperLeft && lowerRight > lowerLeft) return new ClassifiedDigit(3, .76);
            if (upperLeft > .35 && upperRight > .35 && lowerRight > .30) return new ClassifiedDigit(4, .72);
            return null;
        } finally {
            contourInput.release();
            hierarchy.release();
            contours.forEach(Mat::release);
        }
    }

    private double density(Mat glyph, Rect bounds, double x1, double y1, double x2, double y2) {
        int left = bounds.x + Math.min(bounds.width - 1, Math.max(0, (int) Math.floor(bounds.width * x1)));
        int top = bounds.y + Math.min(bounds.height - 1, Math.max(0, (int) Math.floor(bounds.height * y1)));
        int right = bounds.x + Math.min(bounds.width, Math.max(1, (int) Math.ceil(bounds.width * x2)));
        int bottom = bounds.y + Math.min(bounds.height, Math.max(1, (int) Math.ceil(bounds.height * y2)));
        if (right <= left || bottom <= top) return 0;
        Mat region = new Mat(glyph, new Rect(left, top, right - left, bottom - top));
        try {
            return Core.countNonZero(region) / (double) region.total();
        } finally {
            region.release();
        }
    }

    private Mat redMask(Mat color) {
        Mat hsv = new Mat();
        Mat lowRed = new Mat();
        Mat highRed = new Mat();
        Mat red = new Mat();
        Imgproc.cvtColor(color, hsv, Imgproc.COLOR_BGR2HSV);
        Core.inRange(hsv, new Scalar(0, 55, 45), new Scalar(14, 255, 255), lowRed);
        Core.inRange(hsv, new Scalar(165, 55, 45), new Scalar(180, 255, 255), highRed);
        Core.bitwise_or(lowRed, highRed, red);
        hsv.release();
        lowRed.release();
        highRed.release();
        return red;
    }

    private List<Candidate> displayCandidates(Mat color, Mat gray, Mat edges) {
        List<Candidate> result = new ArrayList<>();
        Rect houghDisplay = selectDisplayByHough(edges, color.size());
        if (houghDisplay != null) addCandidate(result, houghDisplay, color.size(), 3.0, true);
        Rect contourDisplay = selectDisplay(edges, color.size());
        if (contourDisplay != null) addCandidate(result, contourDisplay, color.size(), 2.0, true);

        Mat foreground = new Mat();
        Mat connected = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            Imgproc.threshold(gray, foreground, 0, 255, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU);
            int kernelWidth = Math.max(11, color.cols() / 35);
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(kernelWidth, 3));
            try {
                Imgproc.morphologyEx(foreground, connected, Imgproc.MORPH_CLOSE, kernel);
            } finally {
                kernel.release();
            }
            Imgproc.findContours(connected, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            double imageArea = color.size().area();
            for (MatOfPoint contour : contours) {
                Rect raw = Imgproc.boundingRect(contour);
                double aspect = raw.width / (double) Math.max(1, raw.height);
                double areaRatio = raw.area() / imageArea;
                double centerY = (raw.y + raw.height / 2.0) / color.rows();
                if (aspect < 2.2 || aspect > 13 || areaRatio < .003 || areaRatio > .20 || centerY > .68) continue;
                Rect padded = pad(raw, color.size(), .035, .18);
                double geometry = geometryScore(padded, color.size());
                addCandidate(result, padded, color.size(), geometry, true);
            }
        } finally {
            foreground.release();
            connected.release();
            hierarchy.release();
            contours.forEach(Mat::release);
        }

        // A full-frame fallback preserves support for already cropped display photos.
        addCandidate(result, new Rect(0, 0, color.cols(), color.rows()), color.size(), -2.0, false);
        result.sort(Comparator.comparingDouble(Candidate::geometryScore).reversed());
        return result.size() > 12 ? new ArrayList<>(result.subList(0, 12)) : result;
    }

    private void addCandidate(List<Candidate> candidates, Rect rect, Size imageSize, double score, boolean detected) {
        if (rect.width < 20 || rect.height < 12) return;
        for (Candidate candidate : candidates) {
            if (intersectionOverUnion(candidate.rect(), rect) > .72) return;
        }
        candidates.add(new Candidate(rect, score + geometryScore(rect, imageSize), detected));
    }

    private double geometryScore(Rect rect, Size size) {
        double aspect = rect.width / (double) Math.max(1, rect.height);
        double centerY = (rect.y + rect.height / 2.0) / size.height;
        double heightRatio = rect.height / size.height;
        double aspectScore = 1.0 - Math.min(1.0, Math.abs(aspect - 5.0) / 5.0);
        double verticalScore = 1.0 - Math.min(1.0, Math.abs(centerY - .34) / .34);
        double heightScore = 1.0 - Math.min(1.0, Math.abs(heightRatio - .11) / .16);
        return aspectScore * 1.6 + verticalScore * 1.8 + heightScore;
    }

    private Rect pad(Rect rect, Size bounds, double horizontal, double vertical) {
        int dx = (int) Math.round(rect.width * horizontal);
        int dy = (int) Math.round(rect.height * vertical);
        int x = Math.max(0, rect.x - dx);
        int y = Math.max(0, rect.y - dy);
        int right = Math.min((int) bounds.width, rect.x + rect.width + dx);
        int bottom = Math.min((int) bounds.height, rect.y + rect.height + dy);
        return new Rect(x, y, right - x, bottom - y);
    }

    private double intersectionOverUnion(Rect a, Rect b) {
        int left = Math.max(a.x, b.x);
        int top = Math.max(a.y, b.y);
        int right = Math.min(a.x + a.width, b.x + b.width);
        int bottom = Math.min(a.y + a.height, b.y + b.height);
        if (right <= left || bottom <= top) return 0;
        double intersection = (right - left) * (double) (bottom - top);
        return intersection / (a.area() + b.area() - intersection);
    }

    private List<Mat> normalizedVariants(Mat grayCrop) {
        List<Mat> variants = new ArrayList<>();
        variants.add(normalize(grayCrop, .02, false));
        variants.add(normalize(grayCrop, .08, false));
        variants.add(normalize(grayCrop, .13, true));
        variants.add(normalize(grayCrop, .00, true));
        return variants;
    }

    private Mat normalize(Mat source, double verticalTrim, boolean adaptive) {
        int trim = Math.min((int) Math.round(source.rows() * verticalTrim), Math.max(0, source.rows() / 3));
        Rect innerRect = new Rect(0, trim, source.cols(), Math.max(1, source.rows() - trim * 2));
        Mat inner = new Mat(source, innerRect);
        Mat enlarged = new Mat();
        Mat thresholded = new Mat();
        Mat foreground = new Mat();
        Mat verticalLines = new Mat();
        Mat horizontalLines = new Mat();
        Mat cleaned = new Mat();
        try {
            double scale = Math.max(1.0, 260.0 / inner.rows());
            Imgproc.resize(inner, enlarged, new Size(Math.max(80, Math.round(inner.cols() * scale)),
                    Math.max(80, Math.round(inner.rows() * scale))), 0, 0, Imgproc.INTER_CUBIC);
            if (adaptive) {
                int blockSize = Math.max(31, (Math.min(enlarged.rows(), enlarged.cols()) / 8) | 1);
                Imgproc.adaptiveThreshold(enlarged, thresholded, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        Imgproc.THRESH_BINARY, blockSize, 11);
            } else {
                Imgproc.GaussianBlur(enlarged, enlarged, new Size(3, 3), 0);
                Imgproc.threshold(enlarged, thresholded, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
            }
            Core.bitwise_not(thresholded, foreground);

            Mat verticalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                    new Size(Math.max(1, enlarged.cols() / 500), Math.max(18, enlarged.rows() * .78)));
            Mat horizontalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                    new Size(Math.max(30, enlarged.cols() * .42), Math.max(1, enlarged.rows() / 130)));
            try {
                Imgproc.morphologyEx(foreground, verticalLines, Imgproc.MORPH_OPEN, verticalKernel);
                Imgproc.morphologyEx(foreground, horizontalLines, Imgproc.MORPH_OPEN, horizontalKernel);
            } finally {
                verticalKernel.release();
                horizontalKernel.release();
            }
            Core.subtract(foreground, verticalLines, cleaned);
            Core.subtract(cleaned, horizontalLines, cleaned);
            Mat joinKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2));
            try {
                Imgproc.morphologyEx(cleaned, cleaned, Imgproc.MORPH_CLOSE, joinKernel);
            } finally {
                joinKernel.release();
            }
            Core.bitwise_not(cleaned, cleaned);
            Mat bordered = new Mat();
            Core.copyMakeBorder(cleaned, bordered, 24, 24, 24, 24, Core.BORDER_CONSTANT, new Scalar(255));
            return bordered;
        } finally {
            inner.release();
            enlarged.release();
            thresholded.release();
            foreground.release();
            verticalLines.release();
            horizontalLines.release();
            cleaned.release();
        }
    }

    private Attempt runOcr(Mat normalized, Candidate candidate, int redFractionDigits) {
        try {
            MatOfByte encoded = new MatOfByte();
            BufferedImage buffered;
            try {
                Imgcodecs.imencode(".png", normalized, encoded);
                buffered = ImageIO.read(new ByteArrayInputStream(encoded.toArray()));
            } finally {
                encoded.release();
            }
            if (buffered == null) return null;

            Attempt best = null;
            for (int pageSegMode : List.of(7, 8, 6, 13)) {
                String raw;
                double confidence;
                synchronized (tesseract) {
                    tesseract.getClass().getMethod("setPageSegMode", int.class).invoke(tesseract, pageSegMode);
                    raw = ((String) tesseract.getClass().getMethod("doOCR", BufferedImage.class)
                            .invoke(tesseract, buffered)).trim();
                    confidence = wordConfidence(buffered);
                }
                Attempt attempt = parseAttempt(raw, confidence, candidate, redFractionDigits, pageSegMode);
                if (attempt != null && (best == null || attempt.score() > best.score())) best = attempt;
            }
            return best;
        } catch (ReflectiveOperationException | IOException | NumberFormatException ex) {
            return null;
        }
    }

    private Attempt parseAttempt(String raw, double confidence, Candidate candidate, int redFractionDigits, int pageSegMode) {
        String compact = raw.replaceAll("\\s+", "");
        Matcher matcher = DECIMAL.matcher(compact);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) tokens.add(matcher.group());
        if (tokens.isEmpty()) {
            String digits = compact.replaceAll("\\D", "");
            if (digits.length() < 3) return null;
            tokens.add(digits);
        }
        String token = tokens.stream().max(Comparator
                .comparingInt((String item) -> (int) item.chars().filter(Character::isDigit).count())
                .thenComparingInt(item -> item.contains(".") || item.contains(",") ? 1 : 0))
                .orElseThrow().replace(',', '.');
        int digitCount = (int) token.chars().filter(Character::isDigit).count();
        if (!token.contains(".") && redFractionDigits > 0 && digitCount > redFractionDigits) {
            token = token.substring(0, token.length() - redFractionDigits) + "."
                    + token.substring(token.length() - redFractionDigits);
        }
        BigDecimal value = new BigDecimal(token);
        double score = candidate.geometryScore() + confidence * 4 + Math.min(9, digitCount) * .8;
        if (redFractionDigits > 0) score += redFractionDigits * 1.6;
        if (token.contains(".")) score += 4;
        if (pageSegMode == 7) score += .7;
        if (pageSegMode == 13 && digitCount >= 5) score += .3;
        if (token.contains(".") && token.substring(token.indexOf('.') + 1).length() == 1) score -= 2;
        if (digitCount < 4) score -= 5;
        if (digitCount <= 2) score -= 7;
        return new Attempt(value, confidence, raw, candidate, redFractionDigits, score);
    }

    @SuppressWarnings("unchecked")
    private double wordConfidence(BufferedImage buffered) throws ReflectiveOperationException {
        List<Object> words = (List<Object>) tesseract.getClass()
                .getMethod("getWords", BufferedImage.class, int.class).invoke(tesseract, buffered, 2);
        return words.stream().mapToDouble(word -> {
            try {
                return ((Number) word.getClass().getMethod("getConfidence").invoke(word)).doubleValue();
            } catch (ReflectiveOperationException ignored) {
                return 0;
            }
        }).average().orElse(0) / 100.0;
    }

    private int countRedDigitGroups(Mat crop) {
        Mat hsv = new Mat();
        Mat lowRed = new Mat();
        Mat highRed = new Mat();
        Mat red = new Mat();
        try {
            Imgproc.cvtColor(crop, hsv, Imgproc.COLOR_BGR2HSV);
            Core.inRange(hsv, new Scalar(0, 55, 45), new Scalar(14, 255, 255), lowRed);
            Core.inRange(hsv, new Scalar(165, 55, 45), new Scalar(180, 255, 255), highRed);
            Core.bitwise_or(lowRed, highRed, red);

            int fromY = (int) Math.round(red.rows() * .12);
            int toY = Math.max(fromY + 1, (int) Math.round(red.rows() * .88));
            int minimumPixels = Math.max(2, (toY - fromY) / 12);
            int mergeGap = Math.max(2, red.cols() / 90);
            int groups = 0;
            int gap = mergeGap + 1;
            boolean inGroup = false;
            for (int x = 0; x < red.cols(); x++) {
                Mat column = new Mat(red, new Rect(x, fromY, 1, toY - fromY));
                int pixels;
                try {
                    pixels = Core.countNonZero(column);
                } finally {
                    column.release();
                }
                boolean active = pixels >= minimumPixels;
                if (active) {
                    if (!inGroup && gap > mergeGap) groups++;
                    inGroup = true;
                    gap = 0;
                } else if (inGroup) {
                    gap++;
                    if (gap > mergeGap) inGroup = false;
                }
            }
            return Math.min(groups, 4);
        } finally {
            hsv.release();
            lowRed.release();
            highRed.release();
            red.release();
        }
    }

    private BigDecimal confidence(double confidence) {
        return BigDecimal.valueOf(Math.max(0, Math.min(1, confidence))).setScale(4, RoundingMode.HALF_UP);
    }

    private Rect selectDisplay(Mat edges, Size imageSize) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);
        double imageArea = imageSize.area();
        Rect best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            double areaRatio = rect.area() / imageArea;
            double aspect = rect.width / (double) Math.max(1, rect.height);
            if (areaRatio < .008 || areaRatio > .35 || aspect < 2.2 || aspect > 10.0) continue;
            double score = geometryScore(rect, imageSize) + areaRatio * 2;
            boolean boundary = rect.x < 4 || rect.y < 4
                    || rect.x + rect.width > imageSize.width - 4 || rect.y + rect.height > imageSize.height - 4;
            if (boundary) score -= 3;
            if (score > bestScore) {
                bestScore = score;
                best = rect;
            }
        }
        hierarchy.release();
        contours.forEach(Mat::release);
        return best;
    }

    private Rect selectDisplayByHough(Mat edges, Size imageSize) {
        Mat lines = new Mat();
        try {
            Imgproc.HoughLinesP(edges, lines, 1, Math.PI / 180, 55,
                    Math.max(35, imageSize.width * .10), Math.max(8, imageSize.width * .02));
            List<Line> horizontal = new ArrayList<>();
            List<Line> vertical = new ArrayList<>();
            for (int row = 0; row < lines.rows(); row++) {
                double[] value = lines.get(row, 0);
                if (value == null || value.length < 4) continue;
                Line line = new Line(value[0], value[1], value[2], value[3]);
                double angle = Math.abs(Math.toDegrees(Math.atan2(line.y2() - line.y1(), line.x2() - line.x1())));
                double normalized = angle > 90 ? 180 - angle : angle;
                if (normalized <= 12) horizontal.add(line);
                else if (normalized >= 78) vertical.add(line);
            }
            Rect best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (Line top : horizontal) for (Line bottom : horizontal) {
                double y1 = Math.min(top.midY(), bottom.midY());
                double y2 = Math.max(top.midY(), bottom.midY());
                if (y2 - y1 < imageSize.height * .04 || y2 - y1 > imageSize.height * .30) continue;
                for (Line left : vertical) for (Line right : vertical) {
                    double x1 = Math.min(left.midX(), right.midX());
                    double x2 = Math.max(left.midX(), right.midX());
                    if (x2 - x1 < imageSize.width * .12 || x2 - x1 > imageSize.width * .92) continue;
                    Rect rect = pad(new Rect((int) Math.round(x1), (int) Math.round(y1),
                            (int) Math.round(x2 - x1), (int) Math.round(y2 - y1)), imageSize, .025, .16);
                    double areaRatio = rect.area() / imageSize.area();
                    double aspect = rect.width / (double) Math.max(1, rect.height);
                    if (areaRatio < .006 || areaRatio > .35 || aspect < 2.2 || aspect > 12) continue;
                    double score = geometryScore(rect, imageSize) + areaRatio * 3;
                    if (score > bestScore) {
                        bestScore = score;
                        best = rect;
                    }
                }
            }
            return best;
        } finally {
            lines.release();
        }
    }

    private record Candidate(Rect rect, double geometryScore, boolean detected) {}

    private record Line(double x1, double y1, double x2, double y2) {
        double midX() { return (x1 + x2) / 2.0; }
        double midY() { return (y1 + y2) / 2.0; }
    }

    private record Attempt(BigDecimal value, double confidence, String raw, Candidate candidate,
                           int redFractionDigits, double score) {}

    private record MechanicalLayout(Rect rect, double firstCenterX, double pitch,
                                    int totalDigits, int fractionDigits) {}

    private record ClassifiedDigit(int value, double quality) {}
}
