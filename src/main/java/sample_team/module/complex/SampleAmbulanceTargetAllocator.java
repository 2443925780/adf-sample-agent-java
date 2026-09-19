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
import adf.core.component.module.complex.AmbulanceTargetAllocator;
import java.util.ArrayList;
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

public class SampleAmbulanceTargetAllocator extends AmbulanceTargetAllocator {

  private final Set<EntityID> priorityHumans;
  private final Set<EntityID> targetHumans;
  private final Map<EntityID, AmbulanceInfo> agentInfoMap;
  private boolean initialized;

  public SampleAmbulanceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.priorityHumans = new HashSet<>();
    this.targetHumans = new HashSet<>();
    this.agentInfoMap = new HashMap<>();
    this.initialized = false;
  }


  @Override
  public AmbulanceTargetAllocator resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    if (this.getCountResume() >= 2) {
      return this;
    }
    this.initialize();
    return this;
  }


  @Override
  public AmbulanceTargetAllocator preparate() {
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
    for (Map.Entry<EntityID, AmbulanceInfo> entry : this.agentInfoMap
        .entrySet()) {
      if (entry.getValue().target != null) {
        result.put(entry.getKey(), entry.getValue().target);
      }
    }
    return result;
  }


  @Override
  public AmbulanceTargetAllocator calc() {
    this.initialize();
    List<StandardEntity> availableAgents = this.getAvailableAgents();

    this.assignTargets(availableAgents, this.priorityHumans);
    this.assignTargets(availableAgents, this.targetHumans);

    for (StandardEntity agent : availableAgents) {
      EntityID target = this.getNearestTarget(agent);
      if (target == null) {
        break;
      }
      this.assign(agent.getID(), target);
      this.priorityHumans.remove(target);
      this.targetHumans.remove(target);
    }
    return this;
  }


  @Override
  public AmbulanceTargetAllocator updateInfo(MessageManager messageManager) {
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
        .getReceivedMessageList(MessageFireBrigade.class)) {
      MessageFireBrigade fireBrigade = (MessageFireBrigade) message;
      MessageUtil.reflectMessage(this.worldInfo, fireBrigade);
      this.updateTarget(fireBrigade.getAgentID());
    }
    for (CommunicationMessage message : messageManager
        .getReceivedMessageList(MessagePoliceForce.class)) {
      MessagePoliceForce policeForce = (MessagePoliceForce) message;
      MessageUtil.reflectMessage(this.worldInfo, policeForce);
      this.updateTarget(policeForce.getAgentID());
    }
    for (CommunicationMessage message : messageManager
        .getReceivedMessageList(MessageAmbulanceTeam.class)) {
      MessageAmbulanceTeam ambulance = (MessageAmbulanceTeam) message;
      MessageUtil.reflectMessage(this.worldInfo, ambulance);
      this.updateTarget(ambulance.getAgentID());
      this.updateAgent(ambulance);
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
        .getEntityIDsOfType(StandardEntityURN.AMBULANCE_TEAM)) {
      this.agentInfoMap.computeIfAbsent(id, AmbulanceInfo::new);
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
        || !human.isPositionDefined()) {
      return;
    }
    StandardEntity position = this.worldInfo.getPosition(human);
    if (position == null || position.getStandardURN() == REFUGE
        || position.getStandardURN() == AMBULANCE_TEAM) {
      return;
    }

    if (human.isBuriednessDefined() && human.getBuriedness() > 0) {
      if (human.getStandardURN() != CIVILIAN) {
        this.priorityHumans.add(id);
      }
    } else if (human.getStandardURN() == CIVILIAN
        && human.isDamageDefined() && human.getDamage() > 0) {
      this.targetHumans.add(id);
    }
  }


  private List<StandardEntity> getAvailableAgents() {
    List<StandardEntity> result = new ArrayList<>();
    for (StandardEntity entity : this.worldInfo
        .getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM)) {
      AmbulanceInfo info = this.agentInfoMap.get(entity.getID());
      if (info == null) {
        info = new AmbulanceInfo(entity.getID());
        this.agentInfoMap.put(entity.getID(), info);
      }
      if (info.canNewAction && ((AmbulanceTeam) entity).isPositionDefined()) {
        result.add(entity);
      }
    }
    return result;
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


  private EntityID getNearestTarget(StandardEntity agent) {
    StandardEntity nearest = null;
    double bestDistance = Double.MAX_VALUE;
    for (EntityID id : new ArrayList<>(this.targetHumans)) {
      StandardEntity entity = this.worldInfo.getEntity(id);
      if (entity instanceof Human) {
        double distance = this.worldInfo.getDistance(agent, entity);
        if (distance < bestDistance) {
          bestDistance = distance;
          nearest = entity;
        }
      }
    }
    return nearest == null ? null : nearest.getID();
  }


  private void assign(EntityID agentID, EntityID targetID) {
    AmbulanceInfo info = this.agentInfoMap.get(agentID);
    if (info == null) {
      info = new AmbulanceInfo(agentID);
      this.agentInfoMap.put(agentID, info);
    }
    info.target = targetID;
    info.canNewAction = false;
    info.commandTime = this.agentInfo.getTime();
  }


  private void updateAgent(MessageAmbulanceTeam message) {
    AmbulanceInfo info = this.agentInfoMap.get(message.getAgentID());
    if (info == null) {
      info = new AmbulanceInfo(message.getAgentID());
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

    if (message.getAction() == MessageAmbulanceTeam.ACTION_REST
        || message.getAction() == MessageAmbulanceTeam.ACTION_UNLOAD) {
      this.release(info);
      info.canNewAction = true;
    } else if (message.getAction() == MessageAmbulanceTeam.ACTION_MOVE) {
      EntityID messageTarget = message.getTargetID();
      if (messageTarget == null || !messageTarget.equals(info.target)) {
        this.release(info);
        info.canNewAction = true;
      } else {
        info.canNewAction = false;
      }
    } else if (message.getAction() == MessageAmbulanceTeam.ACTION_LOAD
        || message.getAction() == MessageAmbulanceTeam.ACTION_RESCUE) {
      info.canNewAction = false;
    }
  }


  private void completeReport(MessageReport report) {
    AmbulanceInfo info = this.agentInfoMap.get(report.getSenderID());
    if (info != null && report.isDone()) {
      this.release(info);
      info.canNewAction = true;
    }
  }


  private void release(AmbulanceInfo info) {
    if (info.target == null) {
      return;
    }
    this.updateTarget(info.target);
    info.target = null;
  }


  private class AmbulanceInfo {

    private EntityID target;
    private boolean canNewAction;
    private int commandTime;

    AmbulanceInfo(EntityID agentID) {
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
