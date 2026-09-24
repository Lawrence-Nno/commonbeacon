package com.lawrencenno.commonbeacon.offboarding;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Erasure must resume even on installations with transfer storage disabled. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
public class ErasureConfiguration {}
