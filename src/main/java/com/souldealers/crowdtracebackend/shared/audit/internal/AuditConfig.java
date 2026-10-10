package com.souldealers.crowdtracebackend.shared.audit.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(order = 0)
class AuditConfig { }
