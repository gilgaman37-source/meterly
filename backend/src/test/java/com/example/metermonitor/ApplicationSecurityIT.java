package com.example.metermonitor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.metermonitor.ocr.OcrEngine;
import com.example.metermonitor.ocr.OcrRequestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties={"app.ocr.engine=mock","app.ocr.worker-enabled=false"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker=true)
class ApplicationSecurityIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    static final Path uploads=createUploads();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
        registry.add("app.upload.directory",uploads::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OcrRequestService ocrRequests;
    @MockitoBean OcrEngine ocrEngine;

    @Test void userCannotOpenAnalyticsButAnalystCan() throws Exception {
        mvc.perform(get("/api/analytics/summary").with(user("u").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/analytics/summary").with(user("a").roles("ANALYST"))).andExpect(status().isOk());
    }

    @Test void realUserFlowPersistsOwnedMeterReadingAndOcrConfirmation() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String username="user"+suffix, password="correct-password-"+suffix;
        String token=register(username,username+"@example.com",password);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("login",username,"password","wrong-password"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Неверное имя пользователя, электронная почта или пароль"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("login",username,"password",password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isString());

        String create=mvc.perform(post("/api/meters").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("serialNumber","CW-"+suffix,"meterType","COLD_WATER","description","Кухня","region","Московская область"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.serialNumber").value("CW-"+suffix)).andReturn().getResponse().getContentAsString();
        UUID meterId=UUID.fromString(json.readTree(create).get("id").asText());

        String otherToken=register("other"+suffix,"other"+suffix+"@example.com",password);
        mvc.perform(get("/api/meters").header("Authorization","Bearer "+otherToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/meters/"+meterId+"/readings").header("Authorization","Bearer "+otherToken))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/meters/"+meterId+"/readings").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("value",120.5,"meterReset",false))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.source").value("MANUAL"));
        mvc.perform(post("/api/meters/"+meterId+"/readings").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("value",110,"meterReset",false))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].message").value("Показание не может быть меньше предыдущего"));

        when(ocrEngine.recognize(any())).thenReturn(new OcrEngine.Result(new BigDecimal("130.75"),new BigDecimal("0.60"),"130.75",Map.of()));
        String queued=mvc.perform(multipart("/api/meters/"+meterId+"/readings/image").file(testImage()).header("Authorization","Bearer "+token))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED")).andReturn().getResponse().getContentAsString();
        UUID requestId=UUID.fromString(json.readTree(queued).get("requestId").asText());
        ocrRequests.process(requestId);
        mvc.perform(get("/api/reading-requests/"+requestId).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUIRES_CONFIRMATION"))
                .andExpect(jsonPath("$.recognizedValue").value(130.75));
        mvc.perform(patch("/api/reading-requests/"+requestId+"/confirm").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("value",131.25))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/api/meters/"+meterId+"/readings").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].source").value("IMAGE_OCR"));
        mvc.perform(delete("/api/meters/"+meterId).header("Authorization","Bearer "+token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/meters").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].active").value(false));
    }

    private String register(String username,String email,String password) throws Exception {
        String body=mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username",username,"email",email,"password",password))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("USER")).andReturn().getResponse().getContentAsString();
        JsonNode response=json.readTree(body);
        return response.get("token").asText();
    }

    private MockMultipartFile testImage() throws Exception {
        BufferedImage image=new BufferedImage(240,80,BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=image.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0,0,image.getWidth(),image.getHeight());
        graphics.setColor(Color.BLACK); graphics.setFont(new Font(Font.MONOSPACED,Font.BOLD,42)); graphics.drawString("130.75",24,57); graphics.dispose();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); ImageIO.write(image,"png",bytes);
        return new MockMultipartFile("image","meter.png","image/png",bytes.toByteArray());
    }

    private static Path createUploads(){
        try{return Files.createTempDirectory("meterly-it-");}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
}
