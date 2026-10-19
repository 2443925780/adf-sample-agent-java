package sample_team.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.complex.AmbulanceTargetAllocator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import rescuecore2.standard.entities.AmbulanceTeam;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

/**
 * 救护中心的目标分配器。
 *
 * 分工规则（不可改变）：救护车只能救治「已经没被埋」的受伤平民；
 * 仍被埋（buriedness &gt; 0）的人员必须等消防先挖出来，救护车不参与挖掘。
 *
 * 分配策略：
 * <ul>
 *   <li>targetHumans 是「已知可救治伤员」的知识集合，而不是待办队列；
 *       伤员是否仍在集合内由世界模型判定，避免已救走的伤员被反复派车。</li>
 *   <li>正在被某辆车处理的伤员不会被派给第二辆车（全局配对去重）。</li>
 *   <li>按「车-伤员」距离做最小配对的贪心，而不是先到先得，减少空驶。</li>
 * </ul>
 */
public class SampleAmbulanceTargetAllocator extends AmbulanceTargetAllocator {

  /** 已知「已被挖出且受伤」、可被救护车装载的平民。 */
  private final Collection<EntityID> targetHumans;

  private final Map<EntityID, AmbulanceTeamInfo> ambulanceTeamInfoMap;

  public SampleAmbulanceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.targetHumans = new HashSet<>();
    this.ambulanceTeamInfoMap = new HashMap<>();
  }


  @Override
  public AmbulanceTargetAllocator resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    if (this.getCountResume() >= 2) {
      return this;
    }
    this.registerAmbulanceTeams();
    return this;
  }


  @Override
  public AmbulanceTargetAllocator preparate() {
    super.preparate();
    if (this.getCountPreparate() >= 2) {
      return this;
    }
    this.registerAmbulanceTeams();
    return this;
  }


  @Override
  public Map<EntityID, EntityID> getResult() {
    return this.convert(this.ambulanceTeamInfoMap);
  }


  @Override
  public AmbulanceTargetAllocator calc() {
    this.forgetInvalidTargets();

    List<StandardEntity> agents = this.getActionAgents();
    if (agents.isEmpty()) {
      return this;
    }

    Set<EntityID> taken = this.getTakenTargets();
    List<Assignment> assignments = new ArrayList<>();
    for (EntityID targetID : this.targetHumans) {
      if (taken.contains(targetID)) {
        continue;
      }
      StandardEntity target = this.worldInfo.getEntity(targetID);
      if (target == null) {
        continue;
      }
      for (StandardEntity agent : agents) {
        assignments.add(new Assignment(agent.getID(), targetID,
            this.worldInfo.getDistance(agent, target)));
      }
    }
    assignments.sort(Comparator.comparingInt(a -> a.distance));

    Set<EntityID> usedAgents = new HashSet<>();
    Set<EntityID> usedTargets = new HashSet<>();
    for (Assignment assignment : assignments) {
      if (usedAgents.contains(assignment.agentID)
          || usedTargets.contains(assignment.targetID)) {
        continue;
      }
      usedAgents.add(assignment.agentID);
      usedTargets.add(assignment.targetID);
      this.assign(assignment.agentID, assignment.targetID);
    }
    return this;
  }


  @Override
  public AmbulanceTargetAllocator updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    if (this.getCountUpdateInfo() >= 2) {
      return this;
    }
    int currentTime = this.agentInfo.getTime();

    for (CommunicationMessage message : messageManager
        .getReceivedMessageList(MessageCivilian.class)) {
      MessageCivilian mc = (MessageCivilian) message;
      MessageUtil.reflectMessage(this.worldInfo, mc);
      this.classifyCivilian(this.worldInfo.getEntity(mc.getAgentID()));
    }

    for (CommunicationMessage message : messageManager
        .getReceivedMessageList(MessageAmbulanceTeam.class)) {
      MessageAmbulanceTeam mat = (MessageAmbulanceTeam) message;
      MessageUtil.reflectMessage(this.worldInfo, mat);
      AmbulanceTeamInfo info = this.ambulanceTeamInfoMap.get(mat.getAgentID());
      if (info == null) {
        info = new AmbulanceTeamInfo(mat.getAgentID());
      }
      if (currentTime >= info.commandTime + 2) {
        this.ambulanceTeamInfoMap.put(mat.getAgentID(), this.update(info, mat));
      }
    }

    for (CommunicationMessage message : messageManager
        .getReceivedMessageList(MessageReport.class)) {
      MessageReport report = (MessageReport) message;
      AmbulanceTeamInfo info = this.ambulanceTeamInfoMap
          .get(report.getSenderID());
      if (info != null && report.isDone()) {
        info.canNewAction = true;
        info.target = null;
        this.ambulanceTeamInfoMap.put(info.agentID, info);
      }
    }
    return this;
  }


  private void registerAmbulanceTeams() {
    for (EntityID id : this.worldInfo.getEntityIDsOfType(AMBULANCE_TEAM)) {
      this.ambulanceTeamInfoMap.put(id, new AmbulanceTeamInfo(id));
    }
  }


  private void assign(EntityID agentID, EntityID targetID) {
    AmbulanceTeamInfo info = this.ambulanceTeamInfoMap.get(agentID);
    if (info == null) {
      info = new AmbulanceTeamInfo(agentID);
    }
    info.target = targetID;
    info.canNewAction = false;
    info.commandTime = this.agentInfo.getTime();
    this.ambulanceTeamInfoMap.put(agentID, info);
  }


  /** 已经被某辆车认领的伤员，不再派给其他车辆。 */
  private Set<EntityID> getTakenTargets() {
    Set<EntityID> taken = new HashSet<>();
    for (AmbulanceTeamInfo info : this.ambulanceTeamInfoMap.values()) {
      if (info.target != null) {
        taken.add(info.target);
      }
    }
    return taken;
  }


  /** 从世界模型里剔除已经不能救的伤员（被救走、已死亡、已被埋、已到避难所等）。 */
  private void forgetInvalidTargets() {
    for (EntityID targetID : new ArrayList<>(this.targetHumans)) {
      if (!this.isRescuableByAmbulance(this.worldInfo.getEntity(targetID))) {
        this.targetHumans.remove(targetID);
      }
    }
  }


  private void classifyCivilian(StandardEntity entity) {
    if (entity == null) {
      return;
    }
    if (this.isRescuableByAmbulance(entity)) {
      this.targetHumans.add(entity.getID());
    } else {
      this.targetHumans.remove(entity.getID());
    }
  }


  /**
   * 核心规则：救护车只能救治「未被埋」的受伤平民。
   * 被埋（buriedness &gt; 0）的伤员必须等消防先挖出来，救护车不参与。
   */
  private boolean isRescuableByAmbulance(StandardEntity entity) {
    if (entity == null || !(entity instanceof Human)) {
      return false;
    }
    Human human = (Human) entity;
    if (human.getStandardURN() != CIVILIAN) {
      return false;
    }
    if (!human.isHPDefined() || human.getHP() == 0) {
      return false;
    }
    if (!human.isDamageDefined() || human.getDamage() == 0) {
      return false;
    }
    // 核心规则：被埋人员归消防车，救护车不参与挖掘
    if (!human.isBuriednessDefined() || human.getBuriedness() != 0) {
      return false;
    }
    if (!human.isPositionDefined()) {
      return false;
    }
    StandardEntity position = this.worldInfo.getPosition(human);
    if (position == null) {
      return false;
    }
    StandardEntityURN positionURN = position.getStandardURN();
    return positionURN != REFUGE && positionURN != AMBULANCE_TEAM;
  }


  private List<StandardEntity> getActionAgents() {
    List<StandardEntity> result = new ArrayList<>();
    for (StandardEntity entity : this.worldInfo
        .getEntitiesOfType(AMBULANCE_TEAM)) {
      AmbulanceTeamInfo info = this.ambulanceTeamInfoMap.get(entity.getID());
      if (info != null && info.canNewAction
          && ((AmbulanceTeam) entity).isPositionDefined()) {
        result.add(entity);
      }
    }
    return result;
  }


  private AmbulanceTeamInfo update(AmbulanceTeamInfo info,
      MessageAmbulanceTeam message) {
    // 救护车自己被埋：无法行动，释放目标
    if (message.isBuriednessDefined() && message.getBuriedness() > 0) {
      info.canNewAction = false;
      info.target = null;
      return info;
    }

    switch (message.getAction()) {
      case MessageAmbulanceTeam.ACTION_REST:
        info.canNewAction = true;
        info.target = null;
        break;
      case MessageAmbulanceTeam.ACTION_LOAD:
        // 已经把人装上：该伤员从待救集合中移除，避免重复派车
        info.canNewAction = false;
        if (message.getTargetID() != null) {
          this.targetHumans.remove(message.getTargetID());
        }
        break;
      case MessageAmbulanceTeam.ACTION_RESCUE:
        // 救护车不应执行挖掘，若出现则视为空闲
        info.canNewAction = true;
        info.target = null;
        break;
      case MessageAmbulanceTeam.ACTION_UNLOAD:
        info.canNewAction = true;
        info.target = null;
        break;
      case MessageAmbulanceTeam.ACTION_MOVE:
        if (this.isMovingToTarget(info, message.getTargetID())) {
          info.canNewAction = false;
        } else {
          info.canNewAction = true;
          info.target = null;
        }
        break;
      default:
        break;
    }
    return info;
  }


  private boolean isMovingToTarget(AmbulanceTeamInfo info,
      EntityID moveTarget) {
    if (info.target == null || moveTarget == null) {
      return false;
    }
    StandardEntity entity = this.worldInfo.getEntity(moveTarget);
    if (entity == null) {
      return false;
    }
    if (entity.getStandardURN() == REFUGE) {
      // 正在把伤员送往避难所，保持占用
      return true;
    }
    StandardEntity targetEntity = this.worldInfo.getEntity(info.target);
    if (targetEntity == null) {
      return false;
    }
    if (targetEntity instanceof Human) {
      StandardEntity position = this.worldInfo
          .getPosition((Human) targetEntity);
      if (position != null) {
        targetEntity = position;
      }
    }
    return targetEntity.getID().getValue() == entity.getID().getValue();
  }


  private Map<EntityID, EntityID>
      convert(Map<EntityID, AmbulanceTeamInfo> map) {
    Map<EntityID, EntityID> result = new HashMap<>();
    for (Map.Entry<EntityID, AmbulanceTeamInfo> entry : map.entrySet()) {
      if (entry.getValue().target != null) {
        result.put(entry.getKey(), entry.getValue().target);
      }
    }
    return result;
  }


  private class AmbulanceTeamInfo {

    private final EntityID agentID;
    private EntityID target;
    private boolean canNewAction;
    private int commandTime;

    AmbulanceTeamInfo(EntityID agentID) {
      this.agentID = agentID;
      this.target = null;
      this.canNewAction = true;
      this.commandTime = -1;
    }
  }


  private class Assignment {

    private final EntityID agentID;
    private final EntityID targetID;
    private final int distance;

    Assignment(EntityID agentID, EntityID targetID, int distance) {
      this.agentID = agentID;
      this.targetID = targetID;
      this.distance = distance;
    }
  }
}
