package com.lawrencenno.commonbeacon.identity;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OutboxCryptoTest {
    final String key=Base64.getEncoder().encodeToString(new byte[32]);
    @Test void rejectsMissingInvalidDuplicateOrWrongSizedKeysWithoutEchoingSecrets(){
        for(String setting:new String[]{"","a=secret-invalid","a="+Base64.getEncoder().encodeToString(new byte[16]),"a="+key+",a="+key})
            assertThatThrownBy(()->new OutboxCrypto(true,"a",setting)).hasMessage("INVALID_MAIL_KEY_CONFIGURATION").hasNoCause();
        assertThatThrownBy(()->new OutboxCrypto(true,"other","a="+key)).hasMessage("INVALID_MAIL_KEY_CONFIGURATION");
    }
    @Test void ciphertextAuthenticatesSubjectMessageKeyAndBodyAndUsesFreshNonces(){
        var crypto=new OutboxCrypto(true,"a","a="+key);var payload=new OutboxCrypto.Payload("recipient@example.test","a".repeat(43));var first=crypto.encrypt("message/subject",payload);var second=crypto.encrypt("message/subject",payload);
        assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());assertThat(crypto.decrypt("message/subject",first.key(),first.nonce(),first.body()).token()).isEqualTo(payload.token());
        assertThatThrownBy(()->crypto.decrypt("another/subject",first.key(),first.nonce(),first.body())).hasMessage("PAYLOAD_INVALID");
        byte[] altered=first.body();altered[0]^=1;assertThatThrownBy(()->crypto.decrypt("message/subject",first.key(),first.nonce(),altered)).hasMessage("PAYLOAD_INVALID");
        assertThatThrownBy(()->crypto.decrypt("message/subject","lost",first.nonce(),first.body())).hasMessage("KEY_UNAVAILABLE");
        assertThat(first.toString()).isEqualTo("EncryptedMail[REDACTED]");assertThat(crypto.toString()).isEqualTo("OutboxCrypto[REDACTED]");
    }
    @Test void payloadRejectsHeadersAndOversizedSecrets(){
        assertThatThrownBy(()->new OutboxCrypto.Payload("a@example.test\r\nBcc: attacker","a".repeat(43))).hasMessage("INVALID_MAIL_PAYLOAD");
        assertThatThrownBy(()->new OutboxCrypto.Payload("a@example.test","a".repeat(100000))).hasMessage("INVALID_MAIL_PAYLOAD");
    }
}
