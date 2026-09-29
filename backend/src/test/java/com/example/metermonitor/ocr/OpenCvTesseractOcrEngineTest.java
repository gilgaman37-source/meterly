package com.example.metermonitor.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.metermonitor.config.AppProperties;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenCvTesseractOcrEngineTest {
    @TempDir Path temp;

    @Test
    void recognizesValueFromGeneratedDigitalDisplayImage() throws Exception {
        Path image = temp.resolve("digital-display.png");
        BufferedImage display = new BufferedImage(900, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = display.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, display.getWidth(), display.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 150));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.drawString("138.42", 125, 180);
        graphics.dispose();
        ImageIO.write(display, "png", image.toFile());

        OcrEngine.Result result = engine("tessdata").recognize(image);

        assertThat(result.value()).isEqualByComparingTo("138.42");
        assertThat(result.confidence()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(result.rawText()).contains("138.42");
    }

    @Test
    void recognizesGoldenDigitalSamplesExactly() throws Exception {
        OpenCvTesseractOcrEngine ocr = engine("golden-tessdata");
        for (GoldenSample sample : goldenSamples()) {
            Path image = temp.resolve("golden-" + sample.expected().replace('.', '_') + ".png");
            drawDigitalDisplay(image, sample);

            OcrEngine.Result result = ocr.recognize(image);

            assertThat(result.value())
                    .as("OCR value for generated sample %s", sample.expected())
                    .isEqualByComparingTo(sample.expected());
        }
    }

    @Test
    void recognizesRealMechanicalCounterWithRedFractionDigits() throws Exception {
        Path source = Path.of("C:/Users/georg/Downloads/measurement-tracker-master/uploads/"
                + "1776855189647_Снимок экрана 2026-04-22 в 02.18.45.png");
        Assumptions.assumeTrue(Files.exists(source));

        OcrEngine.Result result = engine("real-tessdata").recognize(source);

        assertThat(result.value()).isEqualByComparingTo("58.983");
        assertThat(result.rawText()).isEqualTo("00058.983");
        assertThat(result.metadata()).containsEntry("recognitionMode", "mechanical-digits")
                .containsEntry("redFractionDigits", 3);
    }

    private OpenCvTesseractOcrEngine engine(String directory) throws Exception {
        Path tessdata = Files.createDirectories(temp.resolve(directory));
        try (var trained = getClass().getResourceAsStream("/tessdata/eng.traineddata")) {
            if (trained == null) throw new IllegalStateException("Tess4J eng.traineddata is missing");
            Files.copy(trained, tessdata.resolve("eng.traineddata"));
        }
        AppProperties properties = new AppProperties(
                new AppProperties.Jwt("unused", Duration.ofHours(1)),
                new AppProperties.Reading(new BigDecimal("999999999"), Duration.ZERO, BigDecimal.ZERO),
                new AppProperties.Upload(temp, 10_000_000, List.of("image/png"), Duration.ofDays(1)),
                new AppProperties.Ocr("tesseract", .8, tessdata.toString(), "", "", "python", false),
                new AppProperties.Cors(List.of()));
        return new OpenCvTesseractOcrEngine(properties);
    }

    private List<GoldenSample> goldenSamples() throws Exception {
        try (var stream = getClass().getResourceAsStream("/ocr/golden-digital-samples.csv")) {
            if (stream == null) throw new IllegalStateException("golden-digital-samples.csv is missing");
            List<String> lines = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .lines().skip(1).filter(line -> !line.isBlank()).toList();
            List<GoldenSample> samples = new ArrayList<>();
            for (String line : lines) {
                String[] parts = line.split(",");
                samples.add(new GoldenSample(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5])));
            }
            return samples;
        }
    }

    private void drawDigitalDisplay(Path image, GoldenSample sample) throws Exception {
        BufferedImage display = new BufferedImage(sample.width(), sample.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = display.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, display.getWidth(), display.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, sample.fontSize()));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.drawString(sample.expected(), sample.x(), sample.y());
        graphics.dispose();
        ImageIO.write(display, "png", image.toFile());
    }

    private record GoldenSample(String expected, int width, int height, int fontSize, int x, int y) {}
}
