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
package org.apache.rocketmq.broker.transaction;

import java.util.concurrent.CompletableFuture;

import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageExtBrokerInner;
import org.apache.rocketmq.remoting.protocol.header.EndTransactionRequestHeader;
import org.apache.rocketmq.store.PutMessageResult;

public interface TransactionalMessageService {

    /**
     * Process prepare message, in common, we should put this message to storage service.
     *
     * @param messageInner Prepare(Half) message.
     * @return Prepare message storage result.
     */
    PutMessageResult prepareMessage(MessageExtBrokerInner messageInner);

    /**
     * <p>
     *     以异步方式处理 Half 消息, 我们应该将此消息放入存储服务
     * </p>
     * Process prepare message in async manner, we should put this message to storage service
     *
     * @param messageInner Prepare(Half) message.
     * @return CompletableFuture of put result, will be completed at put success(flush and replica done)
     */
    CompletableFuture<PutMessageResult> asyncPrepareMessage(MessageExtBrokerInner messageInner);

    /**
     * 当 Half 消息已提交或回滚时, 删除此消息
     *
     * @param messageExt
     */
    boolean deletePrepareMessage(MessageExt messageExt);

    /**
     * Invoked to process commit prepare message.
     *
     * @param requestHeader Commit message request header.
     * @return Operate result contains prepare message and relative error code.
     */
    OperationResult commitMessage(EndTransactionRequestHeader requestHeader);

    /**
     * Invoked to roll back prepare message.
     *
     * @param requestHeader Prepare message request header.
     * @return Operate result contains prepare message and relative error code.
     */
    OperationResult rollbackMessage(EndTransactionRequestHeader requestHeader);

    /**
     * Traverse uncommitted/unroll back half message and send check back request to producer to obtain transaction
     * status.
     *
     * @param transactionTimeout The minimum time of the transactional message to be checked firstly, one message only
     * exceed this time interval that can be checked.
     * @param transactionCheckMax The maximum number of times the message was checked, if exceed this value, this
     * message will be discarded.
     * @param listener When the message is considered to be checked or discarded, the relative method of this class will
     * be invoked.
     */
    void check(long transactionTimeout, int transactionCheckMax, AbstractTransactionalMessageCheckListener listener);

    /**
     * Open transaction service.
     *
     * @return If open success, return true.
     */
    boolean open();

    /**
     * Close transaction service.
     */
    void close();

    TransactionMetrics getTransactionMetrics();

    void setTransactionMetrics(TransactionMetrics transactionMetrics);
}
