/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.broker.transaction.queue;

import io.opentelemetry.api.common.Attributes;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.broker.metrics.BrokerMetricsManager;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.constant.PermName;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageClientIDSetter;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageExtBrokerInner;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.common.sysflag.MessageSysFlag;
import org.apache.rocketmq.common.topic.TopicValidator;
import org.apache.rocketmq.logging.org.slf4j.Logger;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.remoting.protocol.heartbeat.SubscriptionData;
import org.apache.rocketmq.logging.org.slf4j.LoggerFactory;
import org.apache.rocketmq.store.GetMessageResult;
import org.apache.rocketmq.store.MessageStore;
import org.apache.rocketmq.store.PutMessageResult;
import org.apache.rocketmq.store.PutMessageStatus;

import static org.apache.rocketmq.broker.metrics.BrokerMetricsConstant.LABEL_CONSUMER_GROUP;
import static org.apache.rocketmq.broker.metrics.BrokerMetricsConstant.LABEL_IS_SYSTEM;
import static org.apache.rocketmq.broker.metrics.BrokerMetricsConstant.LABEL_TOPIC;

public class TransactionalMessageBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggerName.TRANSACTION_LOGGER_NAME);

    private final ConcurrentHashMap<Integer, MessageQueue> opQueueMap = new ConcurrentHashMap<>();
    private final BrokerController brokerController;
    private final MessageStore store;
    private final SocketAddress storeHost;

    public TransactionalMessageBridge(BrokerController brokerController, MessageStore store) {
        try {
            this.brokerController = brokerController;
            this.store = store;
            this.storeHost =
                    new InetSocketAddress(brokerController.getBrokerConfig().getBrokerIP1(),
                            brokerController.getNettyServerConfig().getListenPort());
        } catch (Exception e) {
            LOGGER.error("Init TransactionBridge error", e);
            throw new RuntimeException(e);
        }

    }

    public long fetchConsumeOffset(MessageQueue mq) {
        long offset = brokerController.getConsumerOffsetManager().queryOffset(TransactionalMessageUtil.buildConsumerGroup(),
                mq.getTopic(), mq.getQueueId());
        if (offset == -1) {
            offset = store.getMinOffsetInQueue(mq.getTopic(), mq.getQueueId());
        }
        return offset;
    }

    public Set<MessageQueue> fetchMessageQueues(String topic) {
        Set<MessageQueue> mqSet = new HashSet<>();
        TopicConfig topicConfig = selectTopicConfig(topic);
        if (topicConfig != null && topicConfig.getReadQueueNums() > 0) {
            for (int i = 0; i < topicConfig.getReadQueueNums(); i++) {
                MessageQueue mq = new MessageQueue();
                mq.setTopic(topic);
                mq.setBrokerName(brokerController.getBrokerConfig().getBrokerName());
                mq.setQueueId(i);
                mqSet.add(mq);
            }
        }
        return mqSet;
    }

    public void updateConsumeOffset(MessageQueue mq, long offset) {
        this.brokerController.getConsumerOffsetManager().commitOffset(
                RemotingHelper.parseSocketAddressAddr(this.storeHost), TransactionalMessageUtil.buildConsumerGroup(), mq.getTopic(),
                mq.getQueueId(), offset);
    }

    public PullResult getHalfMessage(int queueId, long offset, int nums) {
        String group = TransactionalMessageUtil.buildConsumerGroup();
        String topic = TransactionalMessageUtil.buildHalfTopic();
        SubscriptionData sub = new SubscriptionData(topic, "*");
        return getMessage(group, topic, queueId, offset, nums, sub);
    }

    public PullResult getOpMessage(int queueId, long offset, int nums) {
        String group = TransactionalMessageUtil.buildConsumerGroup();
        String topic = TransactionalMessageUtil.buildOpTopic();
        SubscriptionData sub = new SubscriptionData(topic, "*");
        return getMessage(group, topic, queueId, offset, nums, sub);
    }

    private PullResult getMessage(String group, String topic, int queueId, long offset, int nums,
                                  SubscriptionData sub) {
        GetMessageResult getMessageResult = store.getMessage(group, topic, queueId, offset, nums, null);

        if (getMessageResult != null) {
            PullStatus pullStatus = PullStatus.NO_NEW_MSG;
            List<MessageExt> foundList = null;
            switch (getMessageResult.getStatus()) {
                case FOUND:
                    pullStatus = PullStatus.FOUND;
                    foundList = decodeMsgList(getMessageResult);
                    this.brokerController.getBrokerStatsManager().incGroupGetNums(group, topic,
                            getMessageResult.getMessageCount());
                    this.brokerController.getBrokerStatsManager().incGroupGetSize(group, topic,
                            getMessageResult.getBufferTotalSize());
                    this.brokerController.getBrokerStatsManager().incBrokerGetNums(topic, getMessageResult.getMessageCount());
                    if (foundList == null || foundList.size() == 0) {
                        break;
                    }
                    this.brokerController.getBrokerStatsManager().recordDiskFallBehindTime(group, topic, queueId,
                            this.brokerController.getMessageStore().now() - foundList.get(foundList.size() - 1)
                                    .getStoreTimestamp());

                    Attributes attributes = BrokerMetricsManager.newAttributesBuilder()
                            .put(LABEL_TOPIC, topic)
                            .put(LABEL_CONSUMER_GROUP, group)
                            .put(LABEL_IS_SYSTEM, TopicValidator.isSystemTopic(topic) || MixAll.isSysConsumerGroup(group))
                            .build();
                    BrokerMetricsManager.messagesOutTotal.add(getMessageResult.getMessageCount(), attributes);
                    BrokerMetricsManager.throughputOutTotal.add(getMessageResult.getBufferTotalSize(), attributes);

                    break;
                case NO_MATCHED_MESSAGE:
                    pullStatus = PullStatus.NO_MATCHED_MSG;
                    LOGGER.warn("No matched message. GetMessageStatus={}, topic={}, groupId={}, requestOffset={}",
                            getMessageResult.getStatus(), topic, group, offset);
                    break;
                case NO_MESSAGE_IN_QUEUE:
                case OFFSET_OVERFLOW_ONE:
                    pullStatus = PullStatus.NO_NEW_MSG;
                    LOGGER.warn("No new message. GetMessageStatus={}, topic={}, groupId={}, requestOffset={}",
                            getMessageResult.getStatus(), topic, group, offset);
                    break;
                case MESSAGE_WAS_REMOVING:
                case NO_MATCHED_LOGIC_QUEUE:
                case OFFSET_FOUND_NULL:
                case OFFSET_OVERFLOW_BADLY:
                case OFFSET_TOO_SMALL:
                    pullStatus = PullStatus.OFFSET_ILLEGAL;
                    LOGGER.warn("Offset illegal. GetMessageStatus={}, topic={}, groupId={}, requestOffset={}",
                            getMessageResult.getStatus(), topic, group, offset);
                    break;
                default:
                    assert false;
                    break;
            }

            return new PullResult(pullStatus, getMessageResult.getNextBeginOffset(), getMessageResult.getMinOffset(),
                    getMessageResult.getMaxOffset(), foundList);

        } else {
            LOGGER.error("Get message from store return null. topic={}, groupId={}, requestOffset={}", topic, group,
                    offset);
            return null;
        }
    }

    private List<MessageExt> decodeMsgList(GetMessageResult getMessageResult) {
        List<MessageExt> foundList = new ArrayList<>();
        try {
            List<ByteBuffer> messageBufferList = getMessageResult.getMessageBufferList();
            for (ByteBuffer bb : messageBufferList) {
                MessageExt msgExt = MessageDecoder.decode(bb, true, false);
                if (msgExt != null) {
                    foundList.add(msgExt);
                }
            }

        } finally {
            getMessageResult.release();
        }

        return foundList;
    }

    public PutMessageResult putHalfMessage(MessageExtBrokerInner messageInner) {
        return store.putMessage(parseHalfMessageInner(messageInner));
    }

    public CompletableFuture<PutMessageResult> asyncPutHalfMessage(MessageExtBrokerInner messageInner) {
        return store.asyncPutMessage(parseHalfMessageInner(messageInner));
    }

    private MessageExtBrokerInner parseHalfMessageInner(MessageExtBrokerInner msgInner) {
        String uniqId = msgInner.getUserProperty(MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX);
        if (uniqId != null && !uniqId.isEmpty()) {
            MessageAccessor.putProperty(msgInner, TransactionalMessageUtil.TRANSACTION_ID, uniqId);
        }
        MessageAccessor.putProperty(msgInner, MessageConst.PROPERTY_REAL_TOPIC, msgInner.getTopic());
        MessageAccessor.putProperty(msgInner, MessageConst.PROPERTY_REAL_QUEUE_ID, String.valueOf(msgInner.getQueueId()));
        msgInner.setSysFlag(MessageSysFlag.resetTransactionValue(msgInner.getSysFlag(), MessageSysFlag.TRANSACTION_NOT_TYPE));
        msgInner.setTopic(TransactionalMessageUtil.buildHalfTopic());
        msgInner.setQueueId(0);
        msgInner.setPropertiesString(MessageDecoder.messageProperties2String(msgInner.getProperties()));
        return msgInner;
    }

    public PutMessageResult putMessageReturnResult(MessageExtBrokerInner messageInner) {
        LOGGER.debug("[BUG-TO-FIX] Thread:{} msgID:{}", Thread.currentThread().getName(), messageInner.getMsgId());
        PutMessageResult result = store.putMessage(messageInner);
        if (result != null && result.getPutMessageStatus() == PutMessageStatus.PUT_OK) {
            this.brokerController.getBrokerStatsManager().incTopicPutNums(messageInner.getTopic());
            this.brokerController.getBrokerStatsManager().incTopicPutSize(messageInner.getTopic(),
                    result.getAppendMessageResult().getWroteBytes());
            this.brokerController.getBrokerStatsManager().incBrokerPutNums();
        }
        return result;
    }

    public boolean putMessage(MessageExtBrokerInner messageInner) {
        PutMessageResult putMessageResult = store.putMessage(messageInner);
        if (putMessageResult != null
                && putMessageResult.getPutMessageStatus() == PutMessageStatus.PUT_OK) {
            return true;
        } else {
            LOGGER.error("Put message failed, topic: {}, queueId: {}, msgId: {}",
                    messageInner.getTopic(), messageInner.getQueueId(), messageInner.getMsgId());
            return false;
        }
    }

    public MessageExtBrokerInner renewImmunityHalfMessageInner(MessageExt msgExt) {
        MessageExtBrokerInner msgInner = renewHalfMessageInner(msgExt);
        String queueOffsetFromPrepare = msgExt.getUserProperty(MessageConst.PROPERTY_TRANSACTION_PREPARED_QUEUE_OFFSET);
        if (null != queueOffsetFromPrepare) {
            MessageAccessor.putProperty(msgInner, MessageConst.PROPERTY_TRANSACTION_PREPARED_QUEUE_OFFSET,
                    queueOffsetFromPrepare);
        } else {
            MessageAccessor.putProperty(msgInner, MessageConst.PROPERTY_TRANSACTION_PREPARED_QUEUE_OFFSET,
                    String.valueOf(msgExt.getQueueOffset()));
        }

        msgInner.setPropertiesString(MessageDecoder.messageProperties2String(msgInner.getProperties()));

        return msgInner;
    }

    public MessageExtBrokerInner renewHalfMessageInner(MessageExt msgExt) {
        MessageExtBrokerInner msgInner = new MessageExtBrokerInner();
        msgInner.setTopic(msgExt.getTopic());
        msgInner.setBody(msgExt.getBody());
        msgInner.setQueueId(msgExt.getQueueId());
        msgInner.setMsgId(msgExt.getMsgId());
        msgInner.setSysFlag(msgExt.getSysFlag());
        msgInner.setTags(msgExt.getTags());
        msgInner.setTagsCode(MessageExtBrokerInner.tagsString2tagsCode(msgInner.getTags()));
        MessageAccessor.setProperties(msgInner, msgExt.getProperties());
        msgInner.setPropertiesString(MessageDecoder.messageProperties2String(msgExt.getProperties()));
        msgInner.setBornTimestamp(msgExt.getBornTimestamp());
        msgInner.setBornHost(msgExt.getBornHost());
        msgInner.setStoreHost(msgExt.getStoreHost());
        msgInner.setWaitStoreMsgOK(false);
        return msgInner;
    }

    private MessageExtBrokerInner makeOpMessageInner(Message message, MessageQueue messageQueue) {
        MessageExtBrokerInner msgInner = new MessageExtBrokerInner();
        msgInner.setTopic(message.getTopic());
        msgInner.setBody(message.getBody());
        msgInner.setQueueId(messageQueue.getQueueId());
        msgInner.setTags(message.getTags());
        msgInner.setTagsCode(MessageExtBrokerInner.tagsString2tagsCode(msgInner.getTags()));
        msgInner.setSysFlag(0);
        MessageAccessor.setProperties(msgInner, message.getProperties());
        msgInner.setPropertiesString(MessageDecoder.messageProperties2String(message.getProperties()));
        msgInner.setBornTimestamp(System.currentTimeMillis());
        msgInner.setBornHost(this.storeHost);
        msgInner.setStoreHost(this.storeHost);
        msgInner.setWaitStoreMsgOK(false);
        MessageClientIDSetter.setUniqID(msgInner);
        return msgInner;
    }

    private TopicConfig selectTopicConfig(String topic) {
        TopicConfig topicConfig = brokerController.getTopicConfigManager().selectTopicConfig(topic);
        if (topicConfig == null) {
            topicConfig = this.brokerController.getTopicConfigManager().createTopicInSendMessageBackMethod(
                    topic, 1, PermName.PERM_WRITE | PermName.PERM_READ, 0);
        }
        return topicConfig;
    }

    public boolean writeOp(Integer queueId, Message message) {
        MessageQueue opQueue = opQueueMap.get(queueId);

        if (opQueue == null) {
            opQueue = getOpQueueByHalf(queueId, this.brokerController.getBrokerConfig().getBrokerName());
            MessageQueue oldQueue = opQueueMap.putIfAbsent(queueId, opQueue);
            if (oldQueue != null) {
                opQueue = oldQueue;
            }
        }

        PutMessageResult result = putMessageReturnResult(makeOpMessageInner(message, opQueue));
        if (result != null && result.getPutMessageStatus() == PutMessageStatus.PUT_OK) {
            return true;
        }

        return false;
    }

    private MessageQueue getOpQueueByHalf(Integer queueId, String brokerName) {
        MessageQueue opQueue = new MessageQueue();
        opQueue.setTopic(TransactionalMessageUtil.buildOpTopic());
        opQueue.setBrokerName(brokerName);
        opQueue.setQueueId(queueId);
        return opQueue;
    }

    public MessageExt lookMessageByOffset(final long commitLogOffset) {
        return this.store.lookMessageByOffset(commitLogOffset);
    }

    public BrokerController getBrokerController() {
        return brokerController;
    }

    public boolean escapeMessage(MessageExtBrokerInner messageInner) {
        PutMessageResult putMessageResult = this.brokerController.getEscapeBridge().putMessage(messageInner);
        if (putMessageResult != null && putMessageResult.isOk()) {
            return true;
        } else {
            LOGGER.error("Escaping message failed, topic: {}, queueId: {}, msgId: {}",
                    messageInner.getTopic(), messageInner.getQueueId(), messageInner.getMsgId());
            return false;
        }
    }
}
