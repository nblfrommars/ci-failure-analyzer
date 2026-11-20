package com.nbl.cfa.messaging;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String WORKFLOW_RUN_QUEUE = "cfa.workflow-run.completed";

    @Bean
    Queue workflowRunQueue() {
        return QueueBuilder.durable(WORKFLOW_RUN_QUEUE).build();
    }
}