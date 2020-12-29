package com.github.thestyleofme.datax.ribbon;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import com.github.thestyleofme.datax.server.infra.autoconfiguration.DataxZookeeperRegister;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.CollectionUtils;

/**
 * <p>
 * 此类不应该被@ComponentScan扫描到，所以不能放在SpringBoot启动类的包以及子包下，才放在此package下
 * <p>
 * <p>
 * ServerList 从zookeeper中动态获取server
 * IRule负载策略 加权轮询(Weighted Round Robin)
 * </p>
 *
 * @author thestyleofme 2020/12/22 16:31
 * @since 1.0.0
 */
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
@Configuration
@Slf4j
public class DataxRibbonConfiguration {

    @Bean
    public ServerList<Server> ribbonServerList(DataxZookeeperRegister dataxZookeeperRegister) {
        return new DataxServerList(dataxZookeeperRegister);
    }

    @Bean
    public DataxLoadBalanceRule.SmoothWeightedRoundRobin smoothWeightedRoundRobin(DataxZookeeperRegister dataxZookeeperRegister) {
        DataxLoadBalanceRule.SmoothWeightedRoundRobin smoothWeightedRoundRobin =
                new DataxLoadBalanceRule.SmoothWeightedRoundRobin(new CopyOnWriteArrayList<>());
        ThreadFactory factory = new ThreadFactoryBuilder()
                .setNameFormat("RefreshDatax-%d")
                .setDaemon(true)
                .build();
        // 第一次触发时执行一次 而后定时刷新
        refreshDataxServer(dataxZookeeperRegister, smoothWeightedRoundRobin);
        new ScheduledThreadPoolExecutor(2, factory).scheduleWithFixedDelay(
                () -> refreshDataxServer(dataxZookeeperRegister, smoothWeightedRoundRobin),
                1000,
                30000,
                TimeUnit.MILLISECONDS
        );
        return smoothWeightedRoundRobin;
    }

    private static void refreshDataxServer(DataxZookeeperRegister dataxZookeeperRegister, DataxLoadBalanceRule.SmoothWeightedRoundRobin smoothWeightedRoundRobin) {
        log.debug("refresh datax server...");
        List<DataxLoadBalanceRule.SmoothWeightedRoundRobin.Node> currentNodeList = dataxZookeeperRegister.getAllDataxNode().stream()
                .map(registerDataxInfo -> new DataxLoadBalanceRule.SmoothWeightedRoundRobin.Node(registerDataxInfo.getUrl(), registerDataxInfo.getWeight()))
                .collect(Collectors.toList());
        List<DataxLoadBalanceRule.SmoothWeightedRoundRobin.Node> nodeList = smoothWeightedRoundRobin.getNodeList();
        if (CollectionUtils.isEmpty(nodeList)) {
            smoothWeightedRoundRobin.setNodeList(currentNodeList);
        } else {
            // 刷新nodeList
            for (DataxLoadBalanceRule.SmoothWeightedRoundRobin.Node item : nodeList) {
                // 需删除
                boolean anyMatch = currentNodeList.stream().anyMatch(o -> o.getServerName().equals(item.getServerName()));
                if (!anyMatch) {
                    smoothWeightedRoundRobin.removeNode(item.getServerName());
                }
                // 看看是否需要更新权重
                Optional<DataxLoadBalanceRule.SmoothWeightedRoundRobin.Node> first = currentNodeList.stream()
                        .filter(node -> node.getServerName().equals(item.getServerName()) &&
                                node.getWeight() != item.getWeight())
                        .findFirst();
                first.ifPresent(o -> item.setWeight(o.getWeight()));
            }
            for (DataxLoadBalanceRule.SmoothWeightedRoundRobin.Node item : currentNodeList) {
                // 需新增
                boolean anyMatch = nodeList.stream().anyMatch(o -> o.getServerName().equals(item.getServerName()));
                if (!anyMatch) {
                    smoothWeightedRoundRobin.addNode(new DataxLoadBalanceRule.SmoothWeightedRoundRobin.Node(item.getServerName(), item.weight));
                }
            }
        }
    }

    @Bean
    @Primary
    public IRule dataxRibbonRule(DataxLoadBalanceRule.SmoothWeightedRoundRobin smoothWeightedRoundRobin) {
        return new DataxLoadBalanceRule(smoothWeightedRoundRobin);
    }

    /**
     * 从zookeeper中动态获取server
     */
    static class DataxServerList extends AbstractServerList<Server> {

        private final DataxZookeeperRegister dataxZookeeperRegister;

        DataxServerList(DataxZookeeperRegister dataxZookeeperRegister) {
            this.dataxZookeeperRegister = dataxZookeeperRegister;
        }

        @Override
        public List<Server> getInitialListOfServers() {
            return getUpdatedListOfServers();
        }

        @Override
        public List<Server> getUpdatedListOfServers() {
            return dataxZookeeperRegister.getAllDataxNode().stream()
                    .map(registerDataxInfo -> new Server(registerDataxInfo.getUrl()))
                    .collect(Collectors.toList());
        }

        @Override
        public void initWithNiwsConfig(IClientConfig clientConfig) {
            // ignore
        }
    }

    /**
     * <p>
     * 加权轮询(Weighted Round Robin)
     * </p>
     *
     * @author thestyleofme 2020/12/16 9:46
     * @since 1.0.0
     */
    public static class DataxLoadBalanceRule extends AbstractLoadBalancerRule {

        private SmoothWeightedRoundRobin smoothWeightedRoundRobin;

        public DataxLoadBalanceRule(SmoothWeightedRoundRobin smoothWeightedRoundRobin) {
            this.smoothWeightedRoundRobin = smoothWeightedRoundRobin;
        }

        /**
         * 必须有默认构造
         * ribbon底层需要默认的构造函数进行反射
         */
        public DataxLoadBalanceRule() {

        }

        @Override
        public Server choose(Object key) {
            SmoothWeightedRoundRobin.Node node = smoothWeightedRoundRobin.choose();
            return new Server(node.getServerName());
        }

        @Override
        public void initWithNiwsConfig(IClientConfig iClientConfig) {
            // ignore
        }

        static class SmoothWeightedRoundRobin {

            private List<Node> nodeList;
            private final ReentrantLock lock = new ReentrantLock();

            public SmoothWeightedRoundRobin(List<Node> nodeList) {
                this.nodeList = nodeList;
            }

            public List<Node> getNodeList() {
                return nodeList;
            }

            public void setNodeList(List<Node> nodeList) {
                this.nodeList = nodeList;
            }

            public void addNode(Node node) {
                nodeList.add(node);
            }

            public void removeNode(String serviceName) {
                nodeList.removeIf(node -> node.serverName.equals(serviceName));
            }

            public Node choose() {
                lock.lock();
                try {
                    return this.chooseInner();
                } finally {
                    lock.unlock();
                }
            }

            /**
             * 算法逻辑
             * (1) 轮询所有节点，计算当前状态下所有节点的weight之和totalWeight；
             * (2) currentWeight = currentWeight + weight; 选出所有节点中currentWeight中最大的一个节点作为选中节点；
             * (3) 选中节点的currentWeight = currentWeight - totalWeight；
             */
            private Node chooseInner() {
                int totalWeight = 0;
                Node maxNode = null;
                int maxWeight = 0;
                for (Node node : nodeList) {
                    totalWeight += node.getWeight();
                    // 每个节点的当前权重加上原始的权重
                    node.setCurrentWeight(node.getCurrentWeight() + node.getWeight());
                    // 保存当前权重最大的节点
                    if (maxNode == null || maxWeight < node.getCurrentWeight()) {
                        maxNode = node;
                        maxWeight = node.getCurrentWeight();
                    }
                }
                // 被选中的节点权重减掉总权重
                assert maxNode != null;
                maxNode.setCurrentWeight(maxNode.getCurrentWeight() - totalWeight);
                return maxNode;
            }

            static class Node {

                /**
                 * 初始权重
                 */
                private int weight;
                /**
                 * 服务名
                 */
                private final String serverName;
                /**
                 * 当前权重
                 */
                private int currentWeight;

                public Node(String serverName, int weight) {
                    this.serverName = serverName;
                    this.weight = weight;
                    this.currentWeight = weight;
                }

                public int getWeight() {
                    return weight;
                }

                public void setWeight(int weight) {
                    this.weight = weight;
                }

                public String getServerName() {
                    return serverName;
                }

                public int getCurrentWeight() {
                    return currentWeight;
                }

                public void setCurrentWeight(int currentWeight) {
                    this.currentWeight = currentWeight;
                }
            }
        }
    }

}
