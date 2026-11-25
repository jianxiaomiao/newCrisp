package com.newcrisp.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ChatFilesTest {
    @Test
    void recognizesDocumentBytesAndRejectsAFileWithAForgedImageType() throws Exception {
        var pdf = new MockMultipartFile("file", "order.pdf", "application/octet-stream",
                "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(ChatFiles.validate(pdf).info().type()).isEqualTo("application/pdf");
        assertThat(ChatFiles.validate(pdf).sha()).hasSize(32);

        var forged = new MockMultipartFile("file", "fake.png", "image/png", "<svg/>".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> ChatFiles.validate(forged)).hasMessage("UNSUPPORTED_ATTACHMENT_TYPE");
    }
}
