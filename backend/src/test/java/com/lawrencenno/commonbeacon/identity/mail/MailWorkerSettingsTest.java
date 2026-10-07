package com.lawrencenno.commonbeacon.identity.mail;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
class MailWorkerSettingsTest {
    @Test void workerNeedsConfiguredTransportAndRejectsUnsafeBounds(){
        var disabled=new MailSettings(new MockEnvironment(),MailSettingsTest.keys());
        assertThatThrownBy(()->new MailWorkerSettings(new MockEnvironment().withProperty("commonbeacon.email.worker.enabled","true"),disabled)).hasMessage("INVALID_MAIL_WORKER_CONFIGURATION");
        var smtp=new MailSettings(MailSettingsTest.local(1025),MailSettingsTest.keys());
        for(String[] setting:new String[][]{{"concurrency","3"},{"batch-size","21"},{"attempts-per-hour","601"},{"attempts-per-hour","1"},{"timeout-ms","16000"},{"shutdown-ms","21000"},{"interval-ms","0"},{"enabled","unknown"}})
            assertThatThrownBy(()->new MailWorkerSettings(new MockEnvironment().withProperty("commonbeacon.email.worker."+setting[0],setting[1]),smtp)).hasMessage("INVALID_MAIL_WORKER_CONFIGURATION").hasNoCause();
    }
}
