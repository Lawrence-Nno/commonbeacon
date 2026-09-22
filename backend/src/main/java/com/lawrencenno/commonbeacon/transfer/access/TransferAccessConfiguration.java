package com.lawrencenno.commonbeacon.transfer.access;
import java.time.Clock;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
public class TransferAccessConfiguration {
    @Bean Clock transferClock(){return Clock.systemUTC();}
}
