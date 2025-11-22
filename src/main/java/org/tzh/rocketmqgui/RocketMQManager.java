package org.tzh.rocketmqgui;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.MessageSelector;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.admin.ConsumeStats;
import org.apache.rocketmq.common.admin.TopicStatsTable;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.protocol.body.ClusterInfo;
import org.apache.rocketmq.common.protocol.body.ConsumerConnection;
import org.apache.rocketmq.common.protocol.body.SubscriptionGroupWrapper;
import org.apache.rocketmq.common.protocol.body.TopicList;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.Consumer;

public class RocketMQManager {
    private final String namesrvAddr;
    private DefaultMQAdminExt adminExt;
    private DefaultMQProducer producer;
    private DefaultMQPushConsumer consumer;

    public RocketMQManager(String namesrvAddr) throws Exception {
        this.namesrvAddr = namesrvAddr;
        initAdmin();
        initProducer();
    }

    private void initAdmin() throws Exception {
        adminExt = new DefaultMQAdminExt();
        adminExt.setNamesrvAddr(namesrvAddr);
        adminExt.start();
    }

    private void initProducer() throws Exception {
        producer = new DefaultMQProducer("FX_ADMIN_PRODUCER_GROUP");
        producer.setNamesrvAddr(namesrvAddr);
        producer.start();
    }

    // --- 基础信息 ---
    public ClusterInfo getClusterInfo() throws Exception {
        return adminExt.examineBrokerClusterInfo();
    }

    public Set<String> getTopicList() throws Exception {
        TopicList topicList = adminExt.fetchAllTopicList();
        return topicList.getTopicList();
    }

    public SubscriptionGroupWrapper getAllSubscriptionGroups() throws Exception {
        ClusterInfo clusterInfo = adminExt.examineBrokerClusterInfo();
        if (clusterInfo.getBrokerAddrTable().isEmpty()) return null;
        String brokerAddr = clusterInfo.getBrokerAddrTable().values().iterator().next().getBrokerAddrs().get(0L);
        return adminExt.getAllSubscriptionGroup(brokerAddr, 3000);
    }

    // --- 监控数据 ---
    public TopicStatsTable getTopicStats(String topic) throws Exception {
        return adminExt.examineTopicStats(topic);
    }

    // --- 消费组管理 ---
    public ConsumerConnection getConsumerConnection(String group) throws Exception {
        return adminExt.examineConsumerConnectionInfo(group);
    }

    public ConsumeStats getConsumeStats(String group) throws Exception {
        return adminExt.examineConsumeStats(group);
    }

    // --- 高级功能：重置 Offset ---
    public void resetOffset(String topic, String group, long timestamp) throws Exception {
        // isForce=true 强制重置
        adminExt.resetOffsetByTimestamp(topic, group, timestamp, true);
    }

    // --- 生产与消费 ---
    public void createTopic(String topic) throws Exception {
        ClusterInfo clusterInfo = adminExt.examineBrokerClusterInfo();
        String clusterName = clusterInfo.getClusterAddrTable().keySet().iterator().next();
        adminExt.createTopic(clusterName, topic, 4);
    }

    public SendResult sendMessage(String topic, String tag, String body) throws Exception {
        Message msg = new Message(topic, tag, body.getBytes(StandardCharsets.UTF_8));
        return producer.send(msg);
    }

    // 支持 SQL92 过滤的消费者
    public void startConsumer(String group, String topic, String subExpression, boolean isSql92, Consumer<MessageExt> onMessageReceived) throws Exception {
        if (consumer != null) consumer.shutdown();
        consumer = new DefaultMQPushConsumer(group);
        consumer.setNamesrvAddr(namesrvAddr);

        // 设置订阅关系
        if (isSql92) {
            consumer.subscribe(topic, MessageSelector.bySql(subExpression));
        } else {
            consumer.subscribe(topic, subExpression); // 默认为 Tag 模式
        }

        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                onMessageReceived.accept(msg);
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    }

    // 获取死信 Topic 名称
    public String getDLQTopic(String group) {
        return MixAll.DLQ_GROUP_TOPIC_PREFIX + group;
    }

    public MessageExt viewMessage(String msgId) throws Exception {
        return adminExt.viewMessage(msgId);
    }

    public void shutdown() {
        if (adminExt != null) adminExt.shutdown();
        if (producer != null) producer.shutdown();
        if (consumer != null) consumer.shutdown();
    }

    // [新增] 专门用于停止消费者的方法
    public void stopConsumer() {
        if (consumer != null) {
            consumer.shutdown();
            consumer = null; // 置空，以便下次重新创建
        }
    }

    // 在 RocketMQManager.java 中新增

    /**
     * 优雅地关闭所有 RocketMQ 客户端资源
     */
    public void disconnect() throws Exception {
        // 1. 停止任何可能正在运行的消费者（必须先停）
        stopConsumer();

        // 2. 关闭生产者
        if (producer != null) {
            producer.shutdown();
            producer = null;
        }

        // 3. 关闭管理工具
        if (adminExt != null) {
            adminExt.shutdown();
            adminExt = null;
        }
        if (consumer != null) {
            consumer.shutdown();
            consumer = null;
        }
        // 4. 清理配置 (configManager.saveConfig(); 可选，取决于您是否在连接时加载配置)
        // ...
    }
}