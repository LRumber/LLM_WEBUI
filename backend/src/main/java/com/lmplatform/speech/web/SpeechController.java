package com.lmplatform.speech.web;

import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.common.repository.SystemSettingRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Browser speech input API backed only by locally configured speech-to-text models. */
@RestController
@RequestMapping("/api/speech")
public class SpeechController {

    private final AiServiceClient aiServiceClient;
    private final SystemSettingRepository settingRepository;

    public SpeechController(AiServiceClient aiServiceClient, SystemSettingRepository settingRepository) {
        this.aiServiceClient = aiServiceClient;
        this.settingRepository = settingRepository;
    }

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> transcribe(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(new SpeechError("录音内容为空"));
            }
            String mediaType = file.getContentType() == null ? "audio/webm" : file.getContentType();
            if (!mediaType.startsWith("audio/") && !"video/webm".equals(mediaType)) {
                return ResponseEntity.badRequest().body(new SpeechError("仅支持浏览器录制的音频"));
            }
            String text = aiServiceClient.transcribeAudio(
                    file.getBytes(),
                    file.getOriginalFilename() == null ? "recording.webm" : file.getOriginalFilename(),
                    mediaType,
                    settingRepository.modelDefaults().speechModel()
            );
            return ResponseEntity.ok(new SpeechText(text));
        } catch (Exception exception) {
            return ResponseEntity.status(502).body(new SpeechError("语音识别失败，请确认已配置内网 speech_to_text 模型"));
        }
    }

    private record SpeechText(String text) {
    }

    private record SpeechError(String message) {
    }
}
