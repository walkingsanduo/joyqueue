package com.jd.journalq.broker.kafka.consumer;


import com.jd.journalq.broker.kafka.conf.KafkaConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.util.Properties;

/**
 * Consumer
 *
 * @author luoruiheng
 * @since 2/24/17
 */
public class Consumer {

    private final KafkaConsumer<String, String> consumer;
    private final String topic;

    public Consumer(String topic) {
        //super("KafkaConsumerExample", false);
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaConfigs.BOOTSTRAP);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, KafkaConfigs.GROUP_ID);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, KafkaConfigs.GROUP_ID);
//        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
//        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
//        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");

        consumer = new KafkaConsumer<>(props);
        this.topic = topic;
    }

    public KafkaConsumer getKafkaConsumer() {
        return consumer;
    }

    public void doWork() {
        ConsumerRecords<String, String> records = consumer.poll(100);
        for (ConsumerRecord<String, String> record : records) {
            System.out.println("Received message: (" + record.key() + ", " + record.value() + ") at partition "
                    + record.partition() + " at offset " + record.offset() + ", timestamp: " + record.timestamp());

//            try {
//                Thread.currentThread().sleep(100 * 1);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
        }
    }

    public void doStop() {
        if (null != consumer) {
            consumer.close();
        }
    }
}