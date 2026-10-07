package com.lawrencenno.commonbeacon.identity;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** External key ring: new messages use active ID; old IDs remain decrypt-only until drained. */
@Component
public class OutboxCrypto {
    @JsonAutoDetect(fieldVisibility=JsonAutoDetect.Visibility.NONE,getterVisibility=JsonAutoDetect.Visibility.NONE,isGetterVisibility=JsonAutoDetect.Visibility.NONE)
    public static final class Payload {
        private final String recipient,token;
        public Payload(String recipient,String token){
            if(recipient==null || recipient.length()>254 || recipient.length()<3 || recipient.chars().anyMatch(c->c<=32 || c>=127)
                || token==null || !(token.isEmpty() || token.matches("[A-Za-z0-9_-]{43}")))throw new IllegalArgumentException("INVALID_MAIL_PAYLOAD");
            this.recipient=recipient;this.token=token;
        }
        public String recipient(){return recipient;} public String token(){return token;}
        @Override public String toString(){return "MailPayload[REDACTED]";}
    }
    public static final class Encrypted {
        private final String key;private final byte[] nonce,body;
        private Encrypted(String key,byte[] nonce,byte[] body){this.key=key;this.nonce=nonce.clone();this.body=body.clone();}
        public String key(){return key;}public byte[] nonce(){return nonce.clone();}public byte[] body(){return body.clone();}
        @Override public String toString(){return "EncryptedMail[REDACTED]";}
    }
    private final Map<String,javax.crypto.SecretKey> keys;
    private final String active; private final boolean enabled;private final SecureRandom random=new SecureRandom();
    public OutboxCrypto(@Value("${commonbeacon.email.outbox.enabled:false}") boolean enabled,
        @Value("${commonbeacon.email.outbox.active-key:}") String active,@Value("${commonbeacon.email.outbox.keys:}") String configured){
        this.enabled=enabled;this.active=active;
        try {
            var ring=new HashMap<String,javax.crypto.SecretKey>();
            if(!configured.isBlank())for(String entry:configured.split(",",-1)){
                String[] parts=entry.split("=",2);if(parts.length!=2 || !parts[0].matches("[A-Za-z0-9_-]{1,40}"))throw new IllegalArgumentException();
                byte[] bytes=Base64.getDecoder().decode(parts[1]);if(bytes.length!=32 || ring.put(parts[0],new SecretKeySpec(bytes,"AES"))!=null)throw new IllegalArgumentException();Arrays.fill(bytes,(byte)0);
            }
            if(enabled && !ring.containsKey(active))throw new IllegalArgumentException();keys=Map.copyOf(ring);
        }catch(IllegalArgumentException invalid){throw new IllegalStateException("INVALID_MAIL_KEY_CONFIGURATION");}
    }
    public boolean enabled(){return enabled;}
    public void requireEnabled(){if(!enabled)throw new ApiFailure(503,"EMAIL_DELIVERY_UNAVAILABLE","Email delivery is not configured.");}
    @Override public String toString(){return "OutboxCrypto[REDACTED]";}
    public Encrypted encrypt(String binding,Payload payload){
        requireEnabled();byte[] plain=null;
        try {
            var buffer=new ByteArrayOutputStream();try(var out=new DataOutputStream(buffer)){out.writeUTF(payload.recipient);out.writeUTF(payload.token);}plain=buffer.toByteArray();
            byte[] nonce=new byte[12];random.nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,keys.get(active),new GCMParameterSpec(128,nonce));
            cipher.updateAAD((binding+"/"+active).getBytes(StandardCharsets.UTF_8));return new Encrypted(active,nonce,cipher.doFinal(plain));
        }catch(IOException|GeneralSecurityException failure){throw new IllegalStateException("MAIL_ENCRYPTION_FAILED");}
        finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
    public Payload decrypt(String binding,String key,byte[] nonce,byte[] ciphertext){
        requireEnabled();if(!keys.containsKey(key))throw new IllegalStateException("KEY_UNAVAILABLE");
        byte[] plain=null;
        try {
            if(nonce==null || nonce.length!=12 || ciphertext==null || ciphertext.length<16 || ciphertext.length>4096)throw new IOException();
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,keys.get(key),new GCMParameterSpec(128,nonce));
            cipher.updateAAD((binding+"/"+key).getBytes(StandardCharsets.UTF_8));plain=cipher.doFinal(ciphertext);
            try(var input=new DataInputStream(new ByteArrayInputStream(plain))){var payload=new Payload(input.readUTF(),input.readUTF());if(input.available()!=0)throw new IOException();return payload;}
        }catch(IOException|GeneralSecurityException|IllegalArgumentException invalid){throw new IllegalStateException("PAYLOAD_INVALID");}
        finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
}
