package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.communication.standard.bundle.information.MessagePoliceForce;
import adf.core.agent.communication.standard.bundle.information.MessageRoad;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.complex.PoliceTargetAllocator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SamplePoliceTargetAllocator extends PoliceTargetAllocator {

  private final Set<EntityID> blockedRoads;
  private final Map<EntityID, PoliceForceInfo> agentInfoMap;
  private boolean initialized;

  public SamplePoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.blockedRoads = new HashSet<>();
    this.agentInfoMap = new HashMap<>();
    this.initialized = false;
  }


  @Override
  public PoliceTargetAllocator resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    if (this.getCountResume() >= 2) {
      return this;
    }
    this.initialize();
    return this;
  }


  @Override
  public PoliceTargetAllocator preparate() {
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
    for (Map.Entry<EntityID, PoliceForceInfo> entry : this.agentInfoMap
        .entrySet()) {
      if (entry.getValue().target != null) {
        result.put(entry.getKey(), entry.getValue().target);
      }
    }
    return result;
  }


  @Override
  public PoliceTargetAllocator calc() {
    this.initialize();
    this.refreshBlockades();
    List<StandardEntity> availableAgents = this.getAvailableAgents();
    this.assignTargets(availableAgents);
    return this;
  }


  @Override
  public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    if (this.getCountUpdateInfo() >= 2) {
      return this;
    }
    this.initialize();
    this.refreshBlockades();

    for (CommunicationMessage message : messageManager
        .getReceivedMessageList(MessageRoad.class)) {
      MessageRoad road = (MessageRoad) message;
      MessageUtil.reflectMessage(this.worldInfo, road);
      if (road.isBlockadeDefined()) {
        this.addBlockedRoad(road.getRoadID());
      }
    }
    for (CommunicationMessage message : messageManager
        .getReceivedMessageList(CommandPolice.class)) {
      CommandPolice command = (CommandPolice) message;
      if (command.isBroadcast()
          && command.getAction() == CommandPolice.ACTION_CLEAR
          && command.getTargetID() != null) {
        this.addBlockedRoad(command.getTargetID());
      }
    }
    for (CommunicationMessage message : messageManager
        .getReceivedMessageList(MessagePoliceForce.class)) {
      this.updateAgent((MessagePoliceForce) message);
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
        .getEntityIDsOfType(StandardEntityURN.POLICE_FORCE)) {
      this.agentInfoMap.computeIfAbsent(id, PoliceForceInfo::new);
    }
  }


  private List<StandardEntity> getAvailableAgents() {
    List<StandardEntity> result = new ArrayList<>();
    for (StandardEntity entity : this.worldInfo
        .getEntitiesOfType(StandardEntityURN.POLICE_FORCE)) {
      PoliceForceInfo info = this.agentInfoMap.get(entity.getID());
      if (info == null) {
        info = new PoliceForceInfo(entity.getID());
        this.agentInfoMap.put(entity.getID(), info);
      }
      if (info.canNewAction && ((PoliceForce) entity).isPositionDefined()) {
        result.add(entity);
      }
    }
    return result;
  }


  private void assignTargets(List<StandardEntity> agents) {
    for (EntityID targetID : new ArrayList<>(this.blockedRoads)) {
      if (agents.isEmpty()) {
        return;
      }
      StandardEntity target = this.worldInfo.getEntity(targetID);
      if (!(target instanceof Road)) {
        this.blockedRoads.remove(targetID);
        continue;
      }

      agents.sort(new DistanceSorter(this.worldInfo, target));
      StandardEntity agent = agents.remove(0);
      this.blockedRoads.remove(targetID);
      this.assign(agent.getID(), targetID);
    }
  }


  private void assign(EntityID agentID, EntityID targetID) {
    PoliceForceInfo info = this.agentInfoMap.get(agentID);
    if (info == null) {
      info = new PoliceForceInfo(agentID);
      this.agentInfoMap.put(agentID, info);
    }
    info.target = targetID;
    info.canNewAction = false;
    info.commandTime = this.agentInfo.getTime();
  }


  private void updateAgent(MessagePoliceForce message) {
    MessageUtil.reflectMessage(this.worldInfo, message);
    if (message.getTargetID() != null
        && (message.getAction() == MessagePoliceForce.ACTION_CLEAR
            || message.getAction() == MessagePoliceForce.ACTION_MOVE)) {
      this.addBlockedRoad(message.getTargetID());
    }
    PoliceForceInfo info = this.agentInfoMap.get(message.getAgentID());
    if (info == null) {
      info = new PoliceForceInfo(message.getAgentID());
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

    if (message.getAction() == MessagePoliceForce.ACTION_REST) {
      this.release(info);
      info.canNewAction = true;
    } else if (message.getAction() == MessagePoliceForce.ACTION_CLEAR) {
      info.canNewAction = false;
    } else if (message.getAction() == MessagePoliceForce.ACTION_MOVE) {
      EntityID messageTarget = message.getTargetID();
      if (messageTarget == null || !messageTarget.equals(info.target)) {
        this.release(info);
        info.canNewAction = true;
      } else {
        info.canNewAction = false;
      }
    }
  }


  private void completeReport(MessageReport report) {
    PoliceForceInfo info = this.agentInfoMap.get(report.getSenderID());
    if (info != null && report.isDone()) {
      this.release(info);
      info.canNewAction = true;
    }
  }


  private void release(PoliceForceInfo info) {
    if (info.target == null) {
      return;
    }
    StandardEntity target = this.worldInfo.getEntity(info.target);
    if (target instanceof Road && (!((Road) target).isBlockadesDefined()
        || !((Road) target).getBlockades().isEmpty())) {
      this.blockedRoads.add(info.target);
    }
    info.target = null;
  }


  private void refreshBlockades() {
    for (StandardEntity entity : this.worldInfo
        .getEntitiesOfType(StandardEntityURN.ROAD)) {
      Road road = (Road) entity;
      if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
        this.blockedRoads.add(road.getID());
      } else if (road.isBlockadesDefined()
          && road.getBlockades().isEmpty()) {
        this.blockedRoads.remove(road.getID());
      }
    }
  }


  private void addBlockedRoad(EntityID targetID) {
    StandardEntity entity = this.worldInfo.getEntity(targetID);
    if (entity instanceof Road) {
      this.blockedRoads.add(targetID);
    } else if (entity instanceof Blockade) {
      Blockade blockade = (Blockade) entity;
      if (blockade.isPositionDefined()) {
        this.blockedRoads.add(blockade.getPosition());
      }
    }
  }


  private class PoliceForceInfo {

    private EntityID target;
    private boolean canNewAction;
    private int commandTime;

    PoliceForceInfo(EntityID agentID) {
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
