package com.bank.loan.config;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The producer settings in application.yml must be ones Kafka accepts, and
 * the profiles must keep the platform client conventions.
 */
class KafkaClientConfigurationTest {

    @Test
    void producerTimeoutsLetKafkaConstructTheProducer() throws Exception {
        PropertySource<?> base = load("application.yml");
        long delivery = number(base, "spring.kafka.producer.properties.delivery.timeout.ms");
        long request = number(base, "spring.kafka.producer.properties.request.timeout.ms");
        long linger = number(base, "spring.kafka.producer.properties.linger.ms");

        assertThat(delivery).isGreaterThanOrEqualTo(linger + request);
        assertThat(Duration.parse(String.valueOf(base.getProperty("loan.outbox.relay.send-timeout"))).toMillis())
            .isGreaterThan(delivery);

        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", "localhost:9092");
        config.put("key.serializer", StringSerializer.class);
        config.put("value.serializer", StringSerializer.class);
        config.put("enable.idempotence", true);
        config.put("acks", "all");
        config.put("max.in.flight.requests.per.connection", 5);
        config.put("delivery.timeout.ms", (int) delivery);
        config.put("request.timeout.ms", (int) request);
        config.put("linger.ms", (int) linger);
        config.put("compression.type", "lz4");
        // Construction validates the timeout rule; no broker is contacted.
        new KafkaProducer<String, String>(config).close(Duration.ZERO);
    }

    @Test
    void serviceNeverCreatesTopicsAndConsumesOnlyCommittedRecords() throws Exception {
        PropertySource<?> base = load("application.yml");

        assertThat(base.getProperty("spring.kafka.admin.auto-create")).isEqualTo(false);
        assertThat(base.getProperty("spring.kafka.client-id")).isEqualTo("svc-ln-loan-lifecycle");
        assertThat(base.getProperty("spring.kafka.consumer.isolation-level")).isEqualTo("read_committed");
        assertThat(base.getProperty("spring.kafka.consumer.enable-auto-commit")).isEqualTo(false);
        assertThat(base.getProperty("spring.kafka.producer.acks")).isEqualTo("all");
    }

    @Test
    void mskUsesIamAndStrimziUsesMutualTls() throws Exception {
        assertThat(load("application-kafka-msk.yml").getProperty("spring.kafka.properties.sasl.mechanism"))
            .isEqualTo("AWS_MSK_IAM");
        assertThat(load("application-kafka-strimzi.yml").getProperty("spring.kafka.security.protocol"))
            .isEqualTo("SSL");
    }

    private static long number(PropertySource<?> source, String name) {
        return Long.parseLong(String.valueOf(source.getProperty(name)));
    }

    private static PropertySource<?> load(String file) throws Exception {
        return new YamlPropertySourceLoader().load(file, new ClassPathResource(file)).getFirst();
    }
}
