/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.service;

import org.apache.bookkeeper.mledger.Position;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.TransactionMetadataStoreService;
import org.apache.pulsar.client.api.transaction.TxnID;
import org.apache.pulsar.transaction.coordinator.TransactionCoordinatorID;
import org.apache.pulsar.transaction.coordinator.TransactionMetadataStoreState;
import org.apache.pulsar.transaction.coordinator.TransactionSubscription;
import org.apache.pulsar.transaction.coordinator.TxnMeta;
import org.apache.pulsar.transaction.coordinator.impl.MLTransactionMetadataStore;
import org.apache.pulsar.transaction.coordinator.proto.TxnStatus;
import org.awaitility.Awaitility;
import org.junit.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;

public class TransactionMetadataStoreServiceTest extends BrokerTestBase {

    @BeforeMethod
    @Override
    protected void setup() throws Exception {
        ServiceConfiguration configuration = getDefaultConf();
        configuration.setTransactionCoordinatorEnabled(true);
        super.baseSetup(configuration);
    }

    @AfterMethod(alwaysRun = true)
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    @Test
    public void testAddAndRemoveTransactionMetadataStore() {
        TransactionMetadataStoreService transactionMetadataStoreService = pulsar.getTransactionMetadataStoreService();
        Assert.assertNotNull(transactionMetadataStoreService);

        transactionMetadataStoreService.addTransactionMetadataStore(TransactionCoordinatorID.get(0));
        Awaitility.await().atMost(1000,  TimeUnit.MILLISECONDS).until(() ->
                transactionMetadataStoreService.getStores().size() == 1);

        transactionMetadataStoreService.removeTransactionMetadataStore(TransactionCoordinatorID.get(0));
        Awaitility.await().atMost(1000,  TimeUnit.MILLISECONDS).until(() ->
                transactionMetadataStoreService.getStores().size() == 0);
    }

    @Test
    public void testNewTransaction() throws ExecutionException, InterruptedException {
        TransactionMetadataStoreService transactionMetadataStoreService = pulsar.getTransactionMetadataStoreService();
        transactionMetadataStoreService.addTransactionMetadataStore(TransactionCoordinatorID.get(0));
        transactionMetadataStoreService.addTransactionMetadataStore(TransactionCoordinatorID.get(1));
        transactionMetadataStoreService.addTransactionMetadataStore(TransactionCoordinatorID.get(2));
        Awaitility.await().atMost(1000,  TimeUnit.MILLISECONDS).until(() ->
                transactionMetadataStoreService.getStores().size() == 3);
        TxnID txnID0 = transactionMetadataStoreService.newTransaction(TransactionCoordinatorID.get(0), 5).get();
        TxnID txnID1 = transactionMetadataStoreService.newTransaction(TransactionCoordinatorID.get(1), 5).get();
        TxnID txnID2 = transactionMetadataStoreService.newTransaction(TransactionCoordinatorID.get(2), 5).get();
        Assert.assertEquals(0, txnID0.getMostSigBits());
        Assert.assertEquals(1, txnID1.getMostSigBits());
        Assert.assertEquals(2, txnID2.getMostSigBits());
        transactionMetadataStoreService.removeTransactionMetadataStore(TransactionCoordinatorID.get(0));
        transactionMetadataStoreService.removeTransactionMetadataStore(TransactionCoordinatorID.get(1));
        transactionMetadataStoreService.removeTransactionMetadataStore(TransactionCoordinatorID.get(2));
        Assert.assertEquals(transactionMetadataStoreService.getStores().size(), 0);
    }

    @Test
    public void testAddProducedPartitionToTxn() throws ExecutionException, InterruptedException {
        TransactionMetadataStoreService transactionMetadataStoreService = pulsar.getTransactionMetadataStoreService();
        transactionMetadataStoreService.addTransactionMetadataStore(TransactionCoordinatorID.get(0));
        Awaitility.await().atMost(1000,  TimeUnit.MILLISECONDS).until(() ->
                transactionMetadataStoreService.getStores().size() == 1);
        TxnID txnID = transactionMetadataStoreService.newTransaction(TransactionCoordinatorID.get(0), 5000).get();
        List<String> partitions = new ArrayList<>();
        partitions.add("ptn-0");
        partitions.add("ptn-1");
        partitions.add("ptn-2");
        transactionMetadataStoreService.addProducedPartitionToTxn(txnID, partitions);
        TxnMeta txn = transactionMetadataStoreService.getTxnMeta(txnID).get();
        assertEquals(txn.status(), TxnStatus.OPEN);
        transactionMetadataStoreService.removeTransactionMetadataStore(TransactionCoordinatorID.get(0));
        Assert.assertEquals(transactionMetadataStoreService.getStores().size(), 0);
    }

    @Test
    public void testAddAckedPartitionToTxn() throws ExecutionException, InterruptedException {
        TransactionMetadataStoreService transactionMetadataStoreService = pulsar.getTransactionMetadataStoreService();
        transactionMetadataStoreService.addTransactionMetadataStore(TransactionCoordinatorID.get(0));
        Awaitility.await().atMost(1000,  TimeUnit.MILLISECONDS).until(() ->
                transactionMetadataStoreService.getStores().size() == 1);
        TxnID txnID = transactionMetadataStoreService.newTransaction(TransactionCoordinatorID.get(0), 5000).get();
        List<TransactionSubscription> partitions = new ArrayList<>();
        partitions.add(TransactionSubscription.builder().topic("ptn-1").subscription("sub-1").build());
        partitions.add(TransactionSubscription.builder().topic("ptn-2").subscription("sub-1").build());
        partitions.add(TransactionSubscription.builder().topic("ptn-3").subscription("sub-1").build());
        transactionMetadataStoreService.addAckedPartitionToTxn(txnID, partitions);
        TxnMeta txn = transactionMetadataStoreService.getTxnMeta(txnID).get();
        assertEquals(txn.status(), TxnStatus.OPEN);
        transactionMetadataStoreService.removeTransactionMetadataStore(TransactionCoordinatorID.get(0));
        Assert.assertEquals(transactionMetadataStoreService.getStores().size(), 0);
    }

    @Test
    public void testTimeoutTracker() throws Exception {
        pulsar.getTransactionMetadataStoreService().addTransactionMetadataStore(TransactionCoordinatorID.get(0));
        Awaitility.await().atMost(2000, TimeUnit.MILLISECONDS)
                .until(() -> pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0)) != null);
        MLTransactionMetadataStore transactionMetadataStore =
                (MLTransactionMetadataStore) pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0));
        Method method = TransactionMetadataStoreState.class.getDeclaredMethod("checkIfReady");
        method.setAccessible(true);
        Awaitility.await().atMost(1000, TimeUnit.MILLISECONDS)
                .until(() -> (Boolean) method.invoke(transactionMetadataStore));
        Field field = MLTransactionMetadataStore.class.getDeclaredField("txnMetaMap");
        field.setAccessible(true);
        ConcurrentMap<TxnID, Pair<TxnMeta, List<Position>>> txnMap =
                (ConcurrentMap<TxnID, Pair<TxnMeta, List<Position>>>) field.get(transactionMetadataStore);
        int i = -1;
        while (++i < 1000) {
            try {
                transactionMetadataStore.newTransaction(5000).get();
            } catch (Exception e) {
                //no operation
            }
        }

        txnMap.forEach((txnID, txnMetaListPair) -> {
            Assert.assertEquals(txnMetaListPair.getLeft().status(), TxnStatus.OPEN);
        });
        Awaitility.await().atLeast(5000, TimeUnit.MICROSECONDS).atMost(10000, TimeUnit.MILLISECONDS)
                .until(() -> txnMap.size() == 0);
    }

    @Test
    public void testTimeoutTrackerMultiThreading() throws Exception {
        pulsar.getTransactionMetadataStoreService().addTransactionMetadataStore(TransactionCoordinatorID.get(0));
        Awaitility.await().atMost(2000, TimeUnit.MILLISECONDS)
                .until(() -> pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0)) != null);
        MLTransactionMetadataStore transactionMetadataStore =
                (MLTransactionMetadataStore) pulsar.getTransactionMetadataStoreService()
                        .getStores().get(TransactionCoordinatorID.get(0));
        Method method = TransactionMetadataStoreState.class.getDeclaredMethod("checkIfReady");
        method.setAccessible(true);
        Awaitility.await().atMost(1000, TimeUnit.MILLISECONDS)
                .until(() -> (Boolean) method.invoke(transactionMetadataStore));
        Field field = MLTransactionMetadataStore.class.getDeclaredField("txnMetaMap");
        field.setAccessible(true);
        ConcurrentMap<TxnID, Pair<TxnMeta, List<Position>>> txnMap =
                (ConcurrentMap<TxnID, Pair<TxnMeta, List<Position>>>) field.get(transactionMetadataStore);
        new Thread(() -> {
            int i = -1;
            while (++i < 100) {
                try {
                    transactionMetadataStore.newTransaction(1000);
                } catch (Exception e) {
                    //no operation
                }
            }
        }).start();

        new Thread(() -> {
            int i = -1;
            while (++i < 100) {
                try {
                    transactionMetadataStore.newTransaction(3000);
                } catch (Exception e) {
                    //no operation
                }
            }
        }).start();

        new Thread(() -> {
            int i = -1;
            while (++i < 100) {
                try {
                    transactionMetadataStore.newTransaction(2000);
                } catch (Exception e) {
                    //no operation
                }
            }
        }).start();

        new Thread(() -> {
            int i = -1;
            while (++i < 100) {
                try {
                    transactionMetadataStore.newTransaction(10000);
                } catch (Exception e) {
                    //no operation
                }
            }
        }).start();
        Awaitility.await().atLeast(3000, TimeUnit.MICROSECONDS).atMost(10000, TimeUnit.MILLISECONDS)
                .until(() -> txnMap.size() == 100);
    }
}
