package com.jd.journalq.broker.kafka.coordinator;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.jd.journalq.broker.kafka.command.SyncGroupAssignment;
import com.jd.journalq.broker.kafka.coordinator.callback.JoinCallback;
import com.jd.journalq.broker.kafka.coordinator.callback.SyncCallback;
import com.jd.journalq.broker.kafka.KafkaErrorCode;
import com.jd.journalq.broker.kafka.command.JoinGroupRequest;
import com.jd.journalq.broker.kafka.config.KafkaConfig;
import com.jd.journalq.broker.kafka.coordinator.domain.GroupDescribe;
import com.jd.journalq.broker.kafka.coordinator.domain.GroupJoinGroupResult;
import com.jd.journalq.broker.kafka.coordinator.domain.KafkaCoordinatorGroupMember;
import com.jd.journalq.broker.kafka.coordinator.domain.KafkaCoordinatorGroup;
import com.jd.journalq.broker.kafka.coordinator.domain.GroupState;
import com.jd.journalq.toolkit.service.Service;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GroupBalanceHandler
 * author: gaohaoxiang
 * email: gaohaoxiang@jd.com
 * date: 2018/11/7
 */
public class GroupBalanceHandler extends Service {

    protected Logger logger = LoggerFactory.getLogger(getClass());

    private KafkaConfig config;
    private KafkaCoordinatorGroupManager groupMetadataManager;
    private GroupBalanceManager groupBalanceManager;

    public GroupBalanceHandler(KafkaConfig config, KafkaCoordinatorGroupManager groupMetadataManager, GroupBalanceManager groupBalanceManager) {
        this.config = config;
        this.groupMetadataManager = groupMetadataManager;
        this.groupBalanceManager = groupBalanceManager;
    }

    public void handleJoinGroup(String groupId, String memberId, String clientId, String clientHost, int rebalanceTimeoutMs, int sessionTimeoutMs, String protocolType,
                                Map<String, byte[]> protocols, JoinCallback callback) {

        if (!isStarted()) {
            callback.sendResponseCallback(GroupJoinGroupResult.buildError(memberId, KafkaErrorCode.GROUP_COORDINATOR_NOT_AVAILABLE));
            return;
        }
        if (!validGroupId(groupId)) {
            callback.sendResponseCallback(GroupJoinGroupResult.buildError(memberId, KafkaErrorCode.INVALID_GROUP_ID));
            return;
        }
        if (sessionTimeoutMs < config.getSessionMaxTimeout() || sessionTimeoutMs > config.getSessionMinTimeout()) {
            callback.sendResponseCallback(GroupJoinGroupResult.buildError(memberId, KafkaErrorCode.INVALID_SESSION_TIMEOUT));
            return;
        }

        // only try to create the group if the group is not unknown AND
        // the member id is UNKNOWN, if member is specified but group does not
        // exist we should reject the request
        KafkaCoordinatorGroup group = groupMetadataManager.getGroup(groupId);
        if (group == null) {
            // 没有join的member的memberid为unknown
            if (!memberId.equals(JoinGroupRequest.UNKNOWN_MEMBER_ID)) {
                callback.sendResponseCallback(GroupJoinGroupResult.buildError(memberId, KafkaErrorCode.UNKNOWN_MEMBER_ID));
                return;
            }
            group = groupMetadataManager.getOrCreateGroup(new KafkaCoordinatorGroup(groupId, protocolType));
        }

        synchronized (group) {
            doJoinGroup(group, memberId, clientId, clientHost, rebalanceTimeoutMs, sessionTimeoutMs, protocolType, protocols, callback);
        }
    }

    protected void doJoinGroup(KafkaCoordinatorGroup group, String memberId, String clientId, String clientHost, int rebalanceTimeoutMs, int sessionTimeoutMs, String protocolType,
                               Map<String, byte[]> protocols, JoinCallback callback) {

        if (!group.stateIs(GroupState.EMPTY) &&
                (!group.getProtocolType().equals(protocolType) || !group.supportsProtocols(protocols.keySet()))) {
            // if the new member does not support the group protocol, reject it
            callback.sendResponseCallback(GroupJoinGroupResult.buildError(memberId, KafkaErrorCode.INCONSISTENT_GROUP_PROTOCOL));
            return;
        }
        if (group.stateIs(GroupState.EMPTY) &&
                (protocols.isEmpty() || protocolType.isEmpty())) {
            callback.sendResponseCallback(GroupJoinGroupResult.buildError(memberId, KafkaErrorCode.INCONSISTENT_GROUP_PROTOCOL));
            return;
        }
        if (!memberId.equals(JoinGroupRequest.UNKNOWN_MEMBER_ID) && !group.isHasMember(memberId)) {
            // if the member trying to register with a un-recognized id, send the response to let
            // it reset its member id and retry
            callback.sendResponseCallback(GroupJoinGroupResult.buildError(memberId, KafkaErrorCode.UNKNOWN_MEMBER_ID));
            return;
        }

        switch (group.getState()) {
            case DEAD:
                // if the group is marked as dead, it means some other thread has just removed the group
                // from the coordinator metadata; this is likely that the group has migrated to some other
                // coordinator OR the group is in a transient unstable phase. Let the member retry
                // joining without the specified member id,
                callback.sendResponseCallback(GroupJoinGroupResult.buildError(memberId, KafkaErrorCode.UNKNOWN_MEMBER_ID));
                break;
            case PREPARINGREBALANCE:
                if (memberId.equals(JoinGroupRequest.UNKNOWN_MEMBER_ID)) {
                    groupBalanceManager.addMemberAndRebalance(rebalanceTimeoutMs, sessionTimeoutMs, clientId, clientHost, protocols, group, callback);
                } else {
                    KafkaCoordinatorGroupMember member = group.getMember(memberId);
                    groupBalanceManager.updateMemberAndRebalance(group, member, protocols, callback);
                }
                break;
            case AWAITINGSYNC:
                if (memberId.equals(JoinGroupRequest.UNKNOWN_MEMBER_ID)) {
                    groupBalanceManager.addMemberAndRebalance(rebalanceTimeoutMs, sessionTimeoutMs, clientId, clientHost, protocols, group, callback);
                } else {
                    KafkaCoordinatorGroupMember member = group.getMember(memberId);
                    if (member.matches(protocols)) {
                        // member is joining with the same metadata (which could be because it failed to
                        // receive the initial JoinGroup response), so just return current group information
                        // for the current generation.
                        Map<String, byte[]> members = null;
                        if (memberId.equals(group.getLeaderId())) {
                            members = group.currentMemberMetadata();
                        } else {
                            members = Collections.emptyMap();
                        }
                        GroupJoinGroupResult groupJoinGroupResult = new GroupJoinGroupResult(members, memberId, group.getGenerationId(),
                                group.getProtocol(), group.getLeaderId(), KafkaErrorCode.NONE);
                        callback.sendResponseCallback(groupJoinGroupResult);
                    } else {
                        // member has changed metadata, so force a rebalance
                        groupBalanceManager.updateMemberAndRebalance(group, member, protocols, callback);
                    }
                }
                break;
            case EMPTY:
            case STABLE:
                if (memberId.equals(JoinGroupRequest.UNKNOWN_MEMBER_ID)) {
                    // if the member id is unknown, register the member to the group
                    groupBalanceManager.addMemberAndRebalance(rebalanceTimeoutMs, sessionTimeoutMs, clientId, clientHost, protocols, group, callback);
                } else {
                    KafkaCoordinatorGroupMember member = group.getMember(memberId);
                    if (memberId.equals(group.getLeaderId()) || !member.matches(protocols)) {
                        // force a rebalance if a member has changed metadata or if the leader sends JoinGroup.
                        // The latter allows the leader to trigger rebalances for changes affecting assignment
                        // which do not affect the member metadata (such as topic metadata changes for the consumer)
                        groupBalanceManager.updateMemberAndRebalance(group, member, protocols, callback);
                    } else {
                        // for followers with no actual change to their metadata, just return group information
                        // for the current generation which will allow them to issue SyncGroup
                        GroupJoinGroupResult groupJoinGroupResult = new GroupJoinGroupResult(Collections.emptyMap(), memberId, group.getGenerationId(),
                                group.getProtocol(), group.getLeaderId(), KafkaErrorCode.NONE);
                        callback.sendResponseCallback(groupJoinGroupResult);
                    }
                }
                break;
        }

        if (group.stateIs(GroupState.PREPARINGREBALANCE)) {
            groupBalanceManager.checkAndComplete(group);
        }
    }

    public void handleSyncGroup(String groupId, int generation, String memberId, Map<String, SyncGroupAssignment> groupAssignment, SyncCallback callback) {
        if (!isStarted()) {
            callback.sendResponseCallback(null, KafkaErrorCode.GROUP_COORDINATOR_NOT_AVAILABLE);
            return;
        }

        KafkaCoordinatorGroup group = groupMetadataManager.getGroup(groupId);
        if (group == null) {
            callback.sendResponseCallback(null, KafkaErrorCode.UNKNOWN_MEMBER_ID);
            return;
        }

        synchronized (group) {
            doSyncGroup(group, generation, memberId, groupAssignment, callback);
        }
    }

    protected void doSyncGroup(KafkaCoordinatorGroup group, int generationId, String memberId, Map<String, SyncGroupAssignment> groupAssignment, SyncCallback callback) {
        logger.info("sync group, groupId = {}, memberId = {}, memberCount = {}",
                group.getId(), memberId, group.getAllMemberIds().size());

        if (!group.isHasMember(memberId)) {
            callback.sendResponseCallback(null, KafkaErrorCode.UNKNOWN_MEMBER_ID);
            return;
        }
        if (generationId != group.getGenerationId()) {
            callback.sendResponseCallback(null, KafkaErrorCode.ILLEGAL_GENERATION);
            return;
        }
        switch (group.getState()) {
            case DEAD:
            case EMPTY: {
                callback.sendResponseCallback(null, KafkaErrorCode.UNKNOWN_MEMBER_ID);
                break;
            }
            case PREPARINGREBALANCE: {
                callback.sendResponseCallback(null, KafkaErrorCode.REBALANCE_IN_PROGRESS);
                break;
            }
            case AWAITINGSYNC: {
                group.getMember(memberId).setAwaitingSyncCallback(callback);
                groupBalanceManager.completeAndScheduleNextHeartbeatExpiration(group, group.getMember(memberId));
                // if this is the leader, then we can attempt to persist state and transition to stable
                if (memberId.equals(group.getLeaderId())) {
                    // fill any missing members with an empty assignment
                    List<String> allMembers = group.getAllMemberIds();
                    Set<String> groupAssignments = groupAssignment.keySet();
                    Set<String> missing = Sets.newHashSet();
                    missing.addAll(allMembers);
                    missing.removeAll(groupAssignments);
                    if (!missing.isEmpty()) {
                        for (String member : missing) {
                            groupAssignment.put(member, null);
                        }
                    }

                    // another member may have joined the group while we were awaiting this callback,
                    // so we must ensure we are still in the AwaitingSync state and the same generation
                    // when it gets invoked. if we have transitioned to another state, then do nothing
                    if (group.stateIs(GroupState.AWAITINGSYNC) && generationId == group.getGenerationId()) {
                        logger.info("sync group {}, transition to STABLE state, generation id is {}", group.getId(), generationId);
                        groupBalanceManager.setAndPropagateAssignment(group, groupAssignment);
                        group.transitionStateTo(GroupState.STABLE);
                    }
                }
                break;
            }
            case STABLE: {
                // if the group is stable, we just return the current assignment
                KafkaCoordinatorGroupMember member = group.getMember(memberId);
                callback.sendResponseCallback(member.getAssignment(), KafkaErrorCode.NONE);
                groupBalanceManager.completeAndScheduleNextHeartbeatExpiration(group, group.getMember(memberId));
                break;
            }
        }
    }

    public short handleLeaveGroup(String groupId, String memberId) {
        if (!isStarted()) {
            return KafkaErrorCode.GROUP_COORDINATOR_NOT_AVAILABLE;
        }

        KafkaCoordinatorGroup group = groupMetadataManager.getGroup(groupId);
        if (group == null) {
            // if the group is marked as dead, it means some other thread has just removed the group
            // from the coordinator metadata; this is likely that the group has migrated to some other
            // coordinator OR the group is in a transient unstable phase. Let the consumer to retry
            // joining without specified consumer id,
            return KafkaErrorCode.UNKNOWN_MEMBER_ID;
        }

        logger.info("member leave group, memberId: {}, group: {}, state: {}", memberId, groupId, group.getState());

        synchronized (group) {
            if (group.stateIs(GroupState.DEAD) || !group.isHasMember(memberId)) {
                return KafkaErrorCode.UNKNOWN_MEMBER_ID;
            }
            KafkaCoordinatorGroupMember member = group.getMember(memberId);
            groupBalanceManager.removeHeartbeatForLeavingMember(group, member);
            groupBalanceManager.removeMemberAndUpdateGroup(group, member);
            return KafkaErrorCode.NONE;
        }
    }

    public short handleHeartbeat(String groupId, String memberId, int generationId) {
        if (!isStarted()) {
            return KafkaErrorCode.GROUP_COORDINATOR_NOT_AVAILABLE;
        }

        KafkaCoordinatorGroup group = groupMetadataManager.getGroup(groupId);
        if (group == null) {
            return KafkaErrorCode.UNKNOWN_MEMBER_ID;
        }

        synchronized (group) {
            return doHeartbeat(group, memberId, generationId);
        }
    }

    protected short doHeartbeat(KafkaCoordinatorGroup group, String memberId, int generationId) {
        switch (group.getState()) {
            case DEAD:
            case EMPTY: {
                // if the group is marked as dead, it means some other thread has just removed the group
                // from the coordinator metadata; this is likely that the group has migrated to some other
                // coordinator OR the group is in a transient unstable phase. Let the member retry
                // joining without the specified member id,
                return KafkaErrorCode.UNKNOWN_MEMBER_ID;
            }
            case AWAITINGSYNC: {
                if (!group.isHasMember(memberId)) {
                    return KafkaErrorCode.UNKNOWN_MEMBER_ID;
                }
                return KafkaErrorCode.REBALANCE_IN_PROGRESS;
            }
            case PREPARINGREBALANCE: {
                if (!group.isHasMember(memberId)) {
                    return KafkaErrorCode.UNKNOWN_MEMBER_ID;
                }
                if (generationId != group.getGenerationId()) {
                    return KafkaErrorCode.ILLEGAL_GENERATION;
                }
                KafkaCoordinatorGroupMember member = group.getMember(memberId);
                groupBalanceManager.completeAndScheduleNextHeartbeatExpiration(group, member);
                return KafkaErrorCode.REBALANCE_IN_PROGRESS;
            }
            case STABLE: {
                if (!group.isHasMember(memberId)) {
                    return KafkaErrorCode.UNKNOWN_MEMBER_ID;
                }
                if (generationId != group.getGenerationId()) {
                    return KafkaErrorCode.ILLEGAL_GENERATION;
                }
                KafkaCoordinatorGroupMember member = group.getMember(memberId);
                groupBalanceManager.completeAndScheduleNextHeartbeatExpiration(group, member);
                return KafkaErrorCode.NONE;
            }
            default: {
                logger.error("handle heartbeat, invalid group state {} of group {}",
                        group.getState(), group.getId());
                return KafkaErrorCode.ILLEGAL_GENERATION;
            }
        }
    }

    public List<GroupDescribe> handleDescribeGroups(List<String> groupIds) {
        List<GroupDescribe> groupDescribes = Lists.newLinkedList();
        for (String groupId : groupIds) {
            KafkaCoordinatorGroup group = groupMetadataManager.getGroup(groupId);
            GroupDescribe groupDescribe = buildDescribeGroup(group);
            if (groupDescribe != null) {
                groupDescribes.add(groupDescribe);
            }
        }
        return groupDescribes;
    }

    protected GroupDescribe buildDescribeGroup(KafkaCoordinatorGroup group) {
        if (group == null) {
            return null;
        }

        GroupDescribe groupDescribe = new GroupDescribe();
        groupDescribe.setGroupId(group.getId());
        if (group == null) {
            groupDescribe.setState("");
            groupDescribe.setProtocolType("");
            groupDescribe.setProtocol("");
            return groupDescribe;
        }

        groupDescribe.setProtocolType(group.getProtocolType());
        groupDescribe.setProtocol(group.getProtocol());
        groupDescribe.setState(group.getState().toString());
        groupDescribe.setErrCode(KafkaErrorCode.NONE);
        groupDescribe.setMembers(Lists.newArrayList(group.getAllMembers()));

        return groupDescribe;
    }

    protected boolean validGroupId(String groupId) {
        return StringUtils.isNotBlank(groupId);
    }
}