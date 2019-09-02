package com.newegg.ec.redis.schedule;

import com.alibaba.fastjson.JSONObject;
import com.google.common.base.CaseFormat;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.newegg.ec.redis.entity.*;
import com.newegg.ec.redis.plugin.alert.entity.AlertChannel;
import com.newegg.ec.redis.plugin.alert.entity.AlertRecord;
import com.newegg.ec.redis.plugin.alert.entity.AlertRule;
import com.newegg.ec.redis.plugin.alert.service.IAlertChannelService;
import com.newegg.ec.redis.plugin.alert.service.IAlertRecordService;
import com.newegg.ec.redis.plugin.alert.service.IAlertRuleService;
import com.newegg.ec.redis.plugin.alert.service.INotifyService;
import com.newegg.ec.redis.service.IClusterService;
import com.newegg.ec.redis.service.IGroupService;
import com.newegg.ec.redis.service.INodeInfoService;
import com.newegg.ec.redis.util.SignUtil;
import com.newegg.ec.redis.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.newegg.ec.redis.util.SignUtil.EQUAL_SIGN;
import static javax.management.timer.Timer.ONE_MINUTE;

/**
 * @author Jay.H.Zou
 * @date 7/30/2019
 */
public class AlertMessageSchedule implements IDataCollection, IDataCleanup, ApplicationListener<ContextRefreshedEvent> {

    private static final Logger logger = LoggerFactory.getLogger(AlertMessageSchedule.class);

    /**
     * 0: email
     * 1: wechat web hook
     * 2: dingding web hook
     * 3: wechat app
     */
    private static final int EMAIL = 0;

    private static final int WECHAT_WEB_HOOK = 1;

    private static final int DINGDING_WEB_HOOK = 2;

    private static final int WECHAT_APP = 3;

    @Autowired
    private IGroupService groupService;

    @Autowired
    private IClusterService clusterService;

    @Autowired
    private IAlertRuleService alertRuleService;

    @Autowired
    private IAlertChannelService alertChannelService;

    @Autowired
    private IAlertRecordService alertRecordService;

    @Autowired
    private INodeInfoService nodeInfoService;

    @Autowired
    private INotifyService emailNotify;

    @Autowired
    private INotifyService wechatWebHookNotify;

    @Autowired
    private INotifyService dingDingWebHookNotify;

    @Autowired
    private INotifyService wechatAppNotify;

    @Value("${redis-manager.alert.data-keep-days:15}")
    private int dataKeepDays;

    private static ExecutorService threadPool;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent contextRefreshedEvent) {
        threadPool = new ThreadPoolExecutor(2, 5, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadFactoryBuilder().setNameFormat("redis-notify-pool-thread-%d").build(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 定时获取 node info 进行计算，默认5分钟一次
     */
    @Async
    @Scheduled(cron = "")
    @Override
    public void collect() {
        try {
            List<Group> allGroup = groupService.getAllGroup();
            if (allGroup != null && !allGroup.isEmpty()) {
                allGroup.forEach(group -> {
                    threadPool.submit(new AlertTask(group));
                });
            }
        } catch (Exception e) {
            logger.error("Alert scheduled failed.");
        }
    }

    @Async
    @Scheduled(cron = "")
    @Override
    public void cleanup() {
        try {
            Timestamp earliestTime = TimeUtil.getTime(dataKeepDays * TimeUtil.ONE_DAY);
            alertRecordService.deleteAlertRecordByTime(earliestTime);
        } catch (Exception e) {
            logger.error("Cleanup alert data failed.", e);
        }
    }

    private class AlertTask implements Runnable {

        private Group group;

        public AlertTask(Group group) {
            this.group = group;
        }

        @Override
        public void run() {
            try {
                List<Integer> updateRuleIdList = new ArrayList<>();
                // 获取 cluster
                List<Cluster> clusterList = clusterService.getClusterListByGroupId(group.getGroupId());
                if (clusterList == null || clusterList.isEmpty()) {
                    return;
                }
                clusterList.forEach(cluster -> {
                    List<Integer> ruleIdList = getRuleIdList(cluster);
                    if (ruleIdList.isEmpty()) {
                        return;
                    }
                    // 获取集群规则
                    List<AlertRule> alertRuleList = alertRuleService.getAlertRuleIds(ruleIdList);
                    int clusterId = cluster.getClusterId();
                    // 获取 node info 列表
                    NodeInfoParam nodeInfoParam = new NodeInfoParam(clusterId, NodeInfoType.DataType.NODE, NodeInfoType.TimeType.MINUTE, null);
                    List<NodeInfo> lastTimeNodeInfoList = nodeInfoService.getLastTimeNodeInfoList(nodeInfoParam);
                    // 构建告警记录
                    List<AlertRecord> alertRecordList = new ArrayList<>();
                    alertRuleList.forEach(alertRule -> {
                        lastTimeNodeInfoList.forEach(nodeInfo -> {
                            if (isNotify(nodeInfo, alertRule)) {
                                alertRecordList.add(buildAlertRecord(group, cluster, nodeInfo, alertRule));
                                alertRule.setLastCheckTime(TimeUtil.getCurrentTimestamp());
                                updateRuleIdList.add(alertRule.getRuleId());
                            }
                        });
                    });
                    // 获取告警通道并发送消息
                    Multimap<Integer, AlertChannel> channelMultimap = getChannelClassification(cluster);
                    if (channelMultimap != null && !channelMultimap.isEmpty()) {
                        sendMessage(channelMultimap, alertRecordList);
                    }
                    saveRecordToDB(cluster.getClusterName(), alertRecordList);
                });
                // 更新 alert rule
                updateRuleLastCheckTime(updateRuleIdList);
            } catch (Exception e) {
                logger.error("Alert task failed, " + group, e);
            }
        }
    }


    private List<Integer> getRuleIdList(Cluster cluster) {
        List<Integer> ruleIdList = new ArrayList<>();
        String ruleIds = cluster.getRuleIds();
        String[] ruleIdArr = SignUtil.splitByCommas(ruleIds);
        for (String ruleId : ruleIdArr) {
            ruleIdList.add(Integer.parseInt(ruleId));
        }
        return ruleIdList;
    }

    private Multimap<Integer, AlertChannel> getChannelClassification(Cluster cluster) {
        String channelIds = cluster.getChannelIds();
        if (Strings.isNullOrEmpty(channelIds)) {
            return null;
        }
        List<String> channelIdList = Arrays.asList(SignUtil.splitByCommas(channelIds));
        List<AlertChannel> alertChannelList = alertChannelService.getAlertChannelByIds(channelIdList);
        if (alertChannelList == null || alertChannelList.isEmpty()) {
            return null;
        }
        return classifyChannel(alertChannelList);
    }

    private Multimap<Integer, AlertChannel> classifyChannel(List<AlertChannel> alertChannelList) {
        Multimap<Integer, AlertChannel> channelMultimap = ArrayListMultimap.create();
        alertChannelList.forEach(alertChannel -> {
            channelMultimap.put(alertChannel.getChannelType(), alertChannel);
        });
        return channelMultimap;
    }

    /**
     * 校验是否需要告警
     *
     * @param nodeInfo
     * @param alertRule
     * @return
     */
    private boolean isNotify(NodeInfo nodeInfo, AlertRule alertRule) {
        JSONObject jsonObject = JSONObject.parseObject(JSONObject.toJSONString(nodeInfo));
        // 是否生效
        if (!alertRule.getStatus()) {
            return false;
        }
        // 未到检测时间
        if (System.currentTimeMillis() - alertRule.getLastCheckTime().getTime() < alertRule.getCheckCycle() * ONE_MINUTE) {
            return false;
        }
        String alertKey = alertRule.getAlertKey();
        double alertValue = alertRule.getAlertValue();
        String nodeInfoField = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, alertKey);
        Double actualVal = jsonObject.getDouble(nodeInfoField);
        if (actualVal == null) {
            return false;
        }
        int compareType = alertRule.getCompareType();
        return compare(alertValue, actualVal, compareType);
    }

    /**
     * 比较类型
     * 0: 相等
     * 1: 大于
     * -1: 小于
     * 2: 不等于
     */
    private boolean compare(double alertValue, double actualValue, int compareType) {
        BigDecimal alertValueBigDecimal = BigDecimal.valueOf(actualValue);
        BigDecimal actualValueBigDecimal = BigDecimal.valueOf(actualValue);
        switch (compareType) {
            case 0:
                return alertValueBigDecimal.equals(actualValueBigDecimal);
            case 1:
                return alertValue > actualValue;
            case -1:
                return alertValue < actualValue;
            case 2:
                return !alertValueBigDecimal.equals(actualValueBigDecimal);
            default:
                return false;
        }

    }

    private AlertRecord buildAlertRecord(Group group, Cluster cluster, NodeInfo nodeInfo, AlertRule rule) {
        JSONObject jsonObject = JSONObject.parseObject(JSONObject.toJSONString(nodeInfo));
        AlertRecord record = new AlertRecord();
        String alertKey = rule.getAlertKey();
        String nodeInfoField = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, alertKey);
        Double actualVal = jsonObject.getDouble(nodeInfoField);
        record.setGroupId(group.getGroupId());
        record.setGroupName(group.getGroupName());
        record.setClusterId(cluster.getClusterId());
        record.setClusterName(cluster.getClusterName());
        record.setRedisNode(nodeInfo.getNode());
        record.setAlertRule(rule.getAlertKey() + rule.getCompareType() + rule.getAlertValue());
        record.setActualData(rule.getAlertKey() + EQUAL_SIGN + actualVal);
        record.setGlobal(rule.getGlobal());
        record.setRuleInfo(rule.getRuleInfo());
        record.setUpdateTime(TimeUtil.getCurrentTimestamp());
        return record;
    }

    private void sendMessage(Multimap<Integer, AlertChannel> channelMultimap, List<AlertRecord> alertRecordList) {
        emailNotify.notify(channelMultimap.get(EMAIL), alertRecordList);
        wechatWebHookNotify.notify(channelMultimap.get(WECHAT_WEB_HOOK), alertRecordList);
        dingDingWebHookNotify.notify(channelMultimap.get(DINGDING_WEB_HOOK), alertRecordList);
        wechatAppNotify.notify(channelMultimap.get(WECHAT_APP), alertRecordList);
    }

    private void saveRecordToDB(String clusterName, List<AlertRecord> alertRecordList) {
        try {
            alertRecordService.addAlertRecord(alertRecordList);
        } catch (Exception e) {
            logger.error("Save alert to db failed, cluster name = " + clusterName, e);
        }
    }

    private void updateRuleLastCheckTime(List<Integer> ruleIdList) {
        try {
            alertRuleService.updateAlertRuleLastCheckTime(ruleIdList);
        } catch (Exception e) {
            logger.error("Update alert rule last check time, " + ruleIdList, e);
        }
    }

    /**
     * 基于集群级别的告警
     */
    public class AlertMessageNotify implements Runnable {

        private Cluster cluster;

        private List<AlertRule> alertRuleList;

        public AlertMessageNotify(Cluster cluster, List<AlertRule> alertRuleList) {
            this.cluster = cluster;
            this.alertRuleList = alertRuleList;
        }

        /**
         * 1.获取NodeInfoList
         * 2.匹配规则(检查间间隔)
         * 3.写入 DB
         * 4.发送消息
         */
        @Override
        public void run() {
        }
    }
}
