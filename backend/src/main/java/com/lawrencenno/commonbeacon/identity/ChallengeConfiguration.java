package com.lawrencenno.commonbeacon.identity;

import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class ChallengeConfiguration {
    @Bean Clock challengeClock(){return Clock.systemUTC();}
    @Bean SecureRandom challengeRandom(){return new SecureRandom();}
}
