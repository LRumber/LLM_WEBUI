package com.lmplatform.speech.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.common.model.ModelDefaults;
import com.lmplatform.common.repository.SystemSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

/** Unit tests for browser audio upload validation and local speech gateway handoff. */
class SpeechControllerTest {

    @Test
    void transcribesBrowserRecording() {
        AiServiceClient client = mock(AiServiceClient.class);
        SystemSettingRepository settings = mock(SystemSettingRepository.class);
        when(settings.modelDefaults()).thenReturn(new ModelDefaults("", "", "whisper-local"));
        when(client.transcribeAudio(any(byte[].class), eq("recording.webm"), eq("audio/webm"), eq("whisper-local")))
                .thenReturn("你好");
        MockMultipartFile recording = new MockMultipartFile(
                "file", "recording.webm", "audio/webm", new byte[]{1, 2, 3}
        );

        var response = new SpeechController(client, settings).transcribe(recording);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasToString("SpeechText[text=你好]");
        verify(client).transcribeAudio(new byte[]{1, 2, 3}, "recording.webm", "audio/webm", "whisper-local");
    }

    @Test
    void rejectsNonAudioUpload() {
        MockMultipartFile text = new MockMultipartFile(
                "file", "note.txt", "text/plain", "hello".getBytes()
        );

        var response = new SpeechController(
                mock(AiServiceClient.class), mock(SystemSettingRepository.class)
        ).transcribe(text);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
