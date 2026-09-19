package sample_team.module.comm;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.centralized.CommandAmbulance;
import adf.core.agent.communication.standard.bundle.centralized.CommandFire;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.centralized.CommandScout;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.component.communication.CommunicationMessage;
import adf.impl.module.comm.DefaultMessageCoordinator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;

public class SampleMessageCoordinator extends DefaultMessageCoordinator {

  private static final int BUDGET_PERCENT = 80;
  private static final int ROLE_BUDGET_PERCENT = 50;

  @Override
  public void coordinate(AgentInfo agentInfo, WorldInfo worldInfo,
      ScenarioInfo scenarioInfo, MessageManager messageManager,
      ArrayList<CommunicationMessage> sendMessageList,
      List<List<CommunicationMessage>> channelSendMessageList) {
    super.coordinate(agentInfo, worldInfo, scenarioInfo, messageManager,
        sendMessageList, channelSendMessageList);

    for (int channel = 1; channel < scenarioInfo.getCommsChannelsCount();
        channel++) {
      int budget = getSenderBudget(agentInfo, worldInfo, scenarioInfo, channel);
      channelSendMessageList.set(channel,
          limitMessages(channelSendMessageList.get(channel), budget));
    }
  }


  private int getSenderBudget(AgentInfo agentInfo, WorldInfo worldInfo,
      ScenarioInfo scenarioInfo, int channel) {
    int bandwidth = scenarioInfo.getCommsChannelBandwidth(channel);
    int platoons = countPlatoons(worldInfo, scenarioInfo);
    int centers = countCenters(worldInfo, scenarioInfo);
    boolean center = isCenter(agentInfo);
    int roleAgents = center ? centers : platoons;
    if (roleAgents <= 0) {
      return 0;
    }

    int sharedBudget = bandwidth * BUDGET_PERCENT / 100
        * ROLE_BUDGET_PERCENT / 100 / roleAgents;
    return Math.max(0, sharedBudget);
  }


  private int countPlatoons(WorldInfo worldInfo, ScenarioInfo scenarioInfo) {
    int count = 0;
    count += countAgents(worldInfo, StandardEntityURN.FIRE_BRIGADE,
        scenarioInfo.getScenarioAgentsFb());
    count += countAgents(worldInfo, StandardEntityURN.POLICE_FORCE,
        scenarioInfo.getScenarioAgentsPf());
    count += countAgents(worldInfo, StandardEntityURN.AMBULANCE_TEAM,
        scenarioInfo.getScenarioAgentsAt());
    return count;
  }


  private int countCenters(WorldInfo worldInfo, ScenarioInfo scenarioInfo) {
    int count = 0;
    count += countAgents(worldInfo, StandardEntityURN.FIRE_STATION,
        scenarioInfo.getScenarioAgentsFs());
    count += countAgents(worldInfo, StandardEntityURN.POLICE_OFFICE,
        scenarioInfo.getScenarioAgentsPo());
    count += countAgents(worldInfo, StandardEntityURN.AMBULANCE_CENTRE,
        scenarioInfo.getScenarioAgentsAc());
    return count;
  }


  private boolean isCenter(AgentInfo agentInfo) {
    StandardEntityURN type = agentInfo.me().getStandardURN();
    return type == StandardEntityURN.FIRE_STATION
        || type == StandardEntityURN.POLICE_OFFICE
        || type == StandardEntityURN.AMBULANCE_CENTRE;
  }


  private int countAgents(WorldInfo worldInfo, StandardEntityURN type,
      int scenarioCount) {
    Collection<StandardEntity> entities = worldInfo.getEntitiesOfType(type);
    return Math.max(entities.size(), scenarioCount);
  }


  private List<CommunicationMessage> limitMessages(
      List<CommunicationMessage> messages, int budget) {
    Map<String, CommunicationMessage> uniqueMessages = new LinkedHashMap<>();
    for (CommunicationMessage message : messages) {
      CommunicationMessage current = uniqueMessages.get(message.getCheckKey());
      if (current == null
          || getPriority(message) >= getPriority(current)) {
        uniqueMessages.put(message.getCheckKey(), message);
      }
    }

    List<CommunicationMessage> ordered = new ArrayList<>(
        uniqueMessages.values());
    ordered.sort(Comparator.comparingInt(
        SampleMessageCoordinator::getPriority).reversed());

    int used = 0;
    List<CommunicationMessage> result = new ArrayList<>();
    for (CommunicationMessage message : ordered) {
      int size = getRadioMessageSize(message);
      if (used + size <= budget) {
        result.add(message);
        used += size;
      }
    }
    return result;
  }


  private static int getPriority(CommunicationMessage message) {
    if (message instanceof CommandAmbulance
        || message instanceof CommandFire
        || message instanceof CommandPolice
        || message instanceof CommandScout) {
      return 3;
    }
    if (message instanceof StandardMessage) {
      StandardMessagePriority priority = ((StandardMessage) message)
          .getSendingPriority();
      if (priority == StandardMessagePriority.HIGH) {
        return 2;
      }
      if (priority == StandardMessagePriority.NORMAL) {
        return 1;
      }
    }
    return 0;
  }


  private static int getRadioMessageSize(CommunicationMessage message) {
    // getByteArraySize() returns payload bits; radio adds a 5-bit class ID.
    int payloadBits = message.getByteArraySize();
    return (5 + payloadBits + 7) / 8;
  }
}
