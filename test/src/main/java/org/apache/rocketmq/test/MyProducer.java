package org.apache.rocketmq.test;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.common.RemotingHelper;

import java.util.List;

public class MyProducer {

    public static void main(String[] args) throws Exception {

        // 创建指定分组名的生产者
        DefaultMQProducer producer = new DefaultMQProducer("ProducerGroupName");

        // 启动生产者
        producer.start();

        for (int i = 0; i < 128; i++) {
            try {
                // 构建消息
                Message msg = new Message(
                        "TopicTest",
                        "TagA",
                        "OrderID188",
                        "Hello world".getBytes(RemotingHelper.DEFAULT_CHARSET));

                // 同步发送
                SendResult sendResult = producer.send(msg);

                // 打印发送结果
                System.out.printf("%s%n", sendResult);
            } catch (Exception e) {

            }
        }

        producer.shutdown();
    }

    /**
     * 发送有序消息.
     *  有序消息可以分为 全局有序 和 分区有序
     *      分区有序:将需要保证顺序的消息发送到同一个 consumerQueue 中, consumerQueue由单一消费者顺序消费
     * @throws Exception
     */
    public void sendOrderMsg() throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("producergroup");
        producer.setNamesrvAddr("namesrvaddr");
        producer.start();

        producer.send(new Message(), new MessageQueueSelector() {

            @Override
            public MessageQueue select(List<MessageQueue> mqs, Message msg, Object arg) {
                // 根据 orderId 选择发送的 MessageQueue
                Long orderId = (Long) arg;
                long index = orderId % mqs.size();
                return mqs.get((int) index);
            }

        }, 1L);
    }

}
