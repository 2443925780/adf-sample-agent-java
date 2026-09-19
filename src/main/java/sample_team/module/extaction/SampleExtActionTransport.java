package sample_team.module.extaction;

import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionLoad;
import adf.core.agent.action.ambulance.ActionRescue;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.impl.extaction.DefaultExtActionTransport;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleExtActionTransport extends ExtAction {

  private final DefaultExtActionTransport delegate;
  private EntityID target;

  public SampleExtActionTransport(AgentInfo agentInfo, WorldInfo worldInfo,
      ScenarioInfo scenarioInfo, ModuleManager moduleManager,
      DevelopData developData) {
    super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
    this.delegate = new DefaultExtActionTransport(agentInfo, worldInfo,
        scenarioInfo, moduleManager, developData);
    this.target = null;
  }


  @Override
  public ExtAction precompute(PrecomputeData precomputeData) {
    super.precompute(precomputeData);
    if (this.getCountPrecompute() >= 2) {
      return this;
    }
    this.delegate.precompute(precomputeData);
    return this;
  }


  @Override
  public ExtAction resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    if (this.getCountResume() >= 2) {
      return this;
    }
    this.delegate.resume(precomputeData);
    return this;
  }


  @Override
  public ExtAction preparate() {
    super.preparate();
    if (this.getCountPreparate() >= 2) {
      return this;
    }
    this.delegate.preparate();
    return this;
  }


  @Override
  public ExtAction updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    if (this.getCountUpdateInfo() >= 2) {
      return this;
    }
    this.delegate.updateInfo(messageManager);
    return this;
  }


  @Override
  public ExtAction setTarget(EntityID target) {
    this.target = target;
    this.delegate.setTarget(target);
    return this;
  }


  @Override
  public ExtAction calc() {
    Action closeRangeAction = this.calcCloseRangeAction();
    this.result = closeRangeAction != null
        ? closeRangeAction
        : this.delegate.calc().getAction();
    return this;
  }


  private Action calcCloseRangeAction() {
    if (this.target == null) {
      return null;
    }
    StandardEntity entity = this.worldInfo.getEntity(this.target);
    if (!(entity instanceof Human)) {
      return null;
    }

    Human human = (Human) entity;
    EntityID position = this.agentInfo.getPosition();
    if (position == null || !human.isPositionDefined()
        || !position.equals(human.getPosition())) {
      return null;
    }
    if (human.isHPDefined() && human.getHP() == 0) {
      return null;
    }
    if (human.isBuriednessDefined() && human.getBuriedness() > 0) {
      return new ActionRescue(human);
    }
    if (human.getStandardURN() == StandardEntityURN.CIVILIAN) {
      return new ActionLoad(human.getID());
    }
    return null;
  }
}
