package com.scione.scm.bill.kafka.goods;

import com.scione.kafka.autoconfigure.ScioneKafkaErrorHandler;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class GoodsSyncConfiguration {
    /** 管理客户端沿用Starter的Broker和认证，避免落到spring.kafka的localhost默认地址。 */
    @Bean
    public KafkaAdmin goodsKafkaAdmin(ProducerFactory<String, Object> producerFactory) {
        return new KafkaAdmin(new HashMap<>(producerFactory.getConfigurationProperties()));
    }

    /** 副本数沿用Broker默认配置，不在业务代码中固定为单副本。 */
    @Bean
    public NewTopic goodsCreateTopic(@Value("${scm.goods-sync.topic:scm-company-goods-create}") String topic) {
        return TopicBuilder.name(topic).partitions(3).build();
    }

    /** 外部API可能较慢，每次只拉取一条，避免一批100条超过poll处理时限。 */
    @Bean("goodsSyncKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> goodsSyncKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        Map<String, Object> configs = new HashMap<>(consumerFactory.getConfigurationProperties());
        configs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        configs.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        configs.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600000);
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(configs));
        factory.setBatchListener(false);
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(new ScioneKafkaErrorHandler());
        return factory;
    }

    /** 保留其他未指定scheduler的任务所使用的默认单线程调度器。 */
    @Bean("taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        return new ThreadPoolTaskScheduler();
    }

    /** Kafka确认等待不占用采购合同等现有业务的调度线程。 */
    @Bean("goodsMessageScheduler")
    public ThreadPoolTaskScheduler goodsMessageScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("goods-message-");
        return scheduler;
    }
}
