package sample_team.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_FORCE;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.communication.standard.bundle.information.MessagePoliceForce;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.complex.FireTargetAllocator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleFireTargetAllocator extends FireTargetAllocator {

  private final Set<EntityID> priorityHumans;
  private final Set<EntityID> targetHumans;
  private final Map<EntityID, FireBrigadeInfo> agentInfoMap;
  private boolean initialized;

  public SampleFireTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.priorityHumans = new HashSet<>();
    this.targetHumans = new HashSet<>();
    this.agentInfoMap = new HashMap<>();
    this.initialized = false;
  }


  @Override
  public FireTargetAllocator resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    if (this.getCountResume() >= 2) {
      return this;
    }
    this.initialize();
    return this;
  }


  @Override
  public FireTargetAllocator preparate() {
    super.preparate();
    if (this.getCountPreparate() >= 2) {
      return this;
    }
    this.initialize();
    return this;
  }


  @Override
  public Map<EntityID, EntityID> getResult() {
    Map<EntityID, EntityID> result = new HashMap<>();
    for (Map.Entry<EntityID, FireBrigadeInfo> entry : this.agentInfoMap
        .entrySet()) {
      if (entry.getValue().target != null) {
        result.put(entry.getKey(), entry.getValue().target);
      }
    }
    return result;
  }


  @Override
  public FireTargetAllocator calc() {
    this.initialize();
    List<StandardEntity> availableAgents = this.getAvailableAgents();
    this.assignTargets(availableAgents);
    return this;
  }


  @Override
  public FireTargetAllocator updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    if (this.getCountUpdateInfo() >= 2) {
      return this;
    }
    this.initialize();

    for (CommunicationMessage message : messageManager
        .getReceivedMessageList(MessageCivilian.class)) {
      MessageCivilian civilian = (MessageCivilian) message;
      MessageUtil.reflectMessage(this.worldInfo, civilian);
      this.updateTarget(civilian.getAgentID());
    }
    for (CommunicationMessage message : messageManager
        .getReceivedMessageList(MessageAmbulanceTeam.class)) {
      MessageAmbulanceTeam ambulance = (MessageAmbulanceTeam) message;
      MessageUtil.reflectMessage(this.worldInfo, ambulance);
      this.updateTarget(ambulance.getAgentID());
    }
    for (CommunicationMessage message : messageManager
        .getReceivedMessageList(MessagePoliceForce.class)) {
      MessagePoliceForce police = (MessagePoliceForce) message;
      MessageUtil.reflectMessage(this.worldInfo, police);
      this.updateTarget(police.getAgentID());
    }
    for (CommunicationMessage message : messageManager
        .getReceivedMessageList(MessageFireBrigade.class)) {
      MessageFireBrigade fireBrigade = (MessageFireBrigade) message;
      MessageUtil.reflectMessage(this.worldInfo, fireBrigade);
      this.updateTarget(fireBrigade.getAgentID());
      this.updateAgent(fireBrigade);
    }
    for (CommunicationMessage message : messageManager
        .getReceivedMessageList(MessageReport.class)) {
      this.completeReport((MessageReport) message);
    }
    return this;
  }


  private void initialize() {
    if (this.initialized) {
      return;
    }
    this.initialized = true;

    for (EntityID id : this.worldInfo
        .getEntityIDsOfType(StandardEntityURN.FIRE_BRIGADE)) {
      this.agentInfoMap.computeIfAbsent(id, FireBrigadeInfo::new);
    }
    for (StandardEntity entity : this.worldInfo
        .getEntitiesOfType(CIVILIAN, AMBULANCE_TEAM, FIRE_BRIGADE,
            POLICE_FORCE)) {
      if (entity instanceof Human) {
        this.updateTarget(entity.getID());
      }
    }
  }


  private void updateTarget(EntityID id) {
    this.priorityHumans.remove(id);
    this.targetHumans.remove(id);

    StandardEntity entity = this.worldInfo.getEntity(id);
    if (!(entity instanceof Human)) {
      return;
    }
    Human human = (Human) entity;
    if (!human.isHPDefined() || human.getHP() == 0
        || !human.isPositionDefined()
        || !human.isBuriednessDefined() || human.getBuriedness() <= 0) {
      return;
    }
    StandardEntity position = this.worldInfo.getPosition(human);
    if (position == null || position.getStandardURN() == REFUGE) {
      return;
    }

    if (human.getStandardURN() == CIVILIAN) {
      this.targetHumans.add(id);
    } else {
      this.priorityHumans.add(id);
    }
  }


  private List<StandardEntity> getAvailableAgents() {
    List<StandardEntity> result = new ArrayList<>();
    for (StandardEntity entity : this.worldInfo
        .getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE)) {
      FireBrigadeInfo info = this.agentInfoMap.get(entity.getID());
      if (info == null) {
        info = new FireBrigadeInfo(entity.getID());
        this.agentInfoMap.put(entity.getID(), info);
      }
      if (info.canNewAction && ((FireBrigade) entity).isPositionDefined()) {
        result.add(entity);
      }
    }
    return result;
  }


  private void assignTargets(List<StandardEntity> agents) {
    this.assignTargets(agents, this.priorityHumans);
    this.assignTargets(agents, this.targetHumans);
  }


  private void assignTargets(List<StandardEntity> agents,
      Set<EntityID> candidates) {
    for (EntityID targetID : new ArrayList<>(candidates)) {
      if (agents.isEmpty()) {
        return;
      }
      StandardEntity target = this.worldInfo.getEntity(targetID);
      if (!(target instanceof Human)) {
        candidates.remove(targetID);
        continue;
      }

      agents.sort(new DistanceSorter(this.worldInfo, target));
      StandardEntity agent = agents.remove(0);
      this.priorityHumans.remove(targetID);
      this.targetHumans.remove(targetID);
      this.assign(agent.getID(), targetID);
    }
  }


  private void assign(EntityID agentID, EntityID targetID) {
    FireBrigadeInfo info = this.agentInfoMap.get(agentID);
    if (info == null) {
      info = new FireBrigadeInfo(agentID);
      this.agentInfoMap.put(agentID, info);
    }
    info.target = targetID;
    info.canNewAction = false;
    info.commandTime = this.agentInfo.getTime();
  }


  private void updateAgent(MessageFireBrigade message) {
    FireBrigadeInfo info = this.agentInfoMap.get(message.getAgentID());
    if (info == null) {
      info = new FireBrigadeInfo(message.getAgentID());
      this.agentInfoMap.put(message.getAgentID(), info);
    }
    if (this.agentInfo.getTime() < info.commandTime + 2) {
      return;
    }

    if (message.isBuriednessDefined() && message.getBuriedness() > 0) {
      this.release(info);
      info.canNewAction = false;
      return;
    }

    if (message.getAction() == MessageFireBrigade.ACTION_REST) {
      this.release(info);
      info.canNewAction = true;
    } else if (message.getAction() == MessageFireBrigade.ACTION_MOVE) {
      EntityID messageTarget = message.getTargetID();
      if (messageTarget == null || !messageTarget.equals(info.target)) {
        this.release(info);
        info.canNewAction = true;
      } else {
        info.canNewAction = false;
      }
    } else if (message.getAction() == MessageFireBrigade.ACTION_RESCUE) {
      info.canNewAction = false;
    }
  }


  private void completeReport(MessageReport report) {
    FireBrigadeInfo info = this.agentInfoMap.get(report.getSenderID());
    if (info != null && report.isDone()) {
      this.release(info);
      info.canNewAction = true;
    }
  }


  private void release(FireBrigadeInfo info) {
    if (info.target == null) {
      return;
    }
    this.updateTarget(info.target);
    info.target = null;
  }


  private class FireBrigadeInfo {

    private EntityID target;
    private boolean canNewAction;
    private int commandTime;

    FireBrigadeInfo(EntityID agentID) {
      this.target = null;
      this.canNewAction = true;
      this.commandTime = -1;
    }
  }


  private class DistanceSorter implements Comparator<StandardEntity> {

    private final WorldInfo worldInfo;
    private final StandardEntity reference;

    DistanceSorter(WorldInfo worldInfo, StandardEntity reference) {
      this.worldInfo = worldInfo;
      this.reference = reference;
    }


    @Override
    public int compare(StandardEntity first, StandardEntity second) {
      return Integer.compare(this.worldInfo.getDistance(this.reference, first),
          this.worldInfo.getDistance(this.reference, second));
    }
  }
}
