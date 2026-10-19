package sample_team.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_FORCE;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.HumanDetector;
import adf.core.debug.DefaultLogger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.log4j.Logger;
import rescuecore2.messages.Command;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.EntityID;

/**
 * 人员搜索模块（救护车与消防车共用）。
 *
 * 分工规则（不可改变）：
 * <ul>
 *   <li>消防车（FIRE_BRIGADE）：目标 = 仍被掩埋的人（buriedness &gt; 0），负责把人挖出来。</li>
 *   <li>救护车（AMBULANCE_TEAM）：目标 = 已被挖出（buriedness == 0）的受伤平民，
 *       负责装载并送往避难所。<b>救护车不得把被埋人员当作目标</b>，被埋人员必须等消防先挖出来。</li>
 * </ul>
 */
public class SampleHumanDetector extends HumanDetector {

  private Clustering clustering;

  private EntityID result;

  private Logger logger;

  /** 本智能体是否为救护车（true）/ 消防车（false）。 */
  private final boolean transportRole;

  /**
   * 同类型队友「正盯上谁」的计数：人员 ID -> 有几辆队友在管这件事。
   *
   * <p>来源是无线电里本来就在广播的行动消息（{@code MessageAmbulanceTeam} /
   * {@code MessageFireBrigade}）。不看这些消息，全队会同时扑向同一个人——
   * 实测一局 566 次目标选择里 541 次重复、某一时间步 30 辆车选中同一名平民。
   *
   * <p>但这里<b>不用它来做硬性排除</b>，只用来排序（见 {@link #responderCount}）。
   * 早先版本用「已被认领就排除」+「只允许最近的 2 辆」，结果埋点显示
   * 消防车有 <b>36.8%</b> 的时间步是「有候选却被过滤掉」——因为自己的分区里没人可挖、
   * 跨区又被名次卡住，就干脆闲置了。软排序既能把人摊开，又不会让谁没活干。
   */
  private final Map<EntityID, Integer> respondersByHuman;

  /**
   * 同类型队友正赶往哪个<b>区域</b>的计数：区域 ID -> 有几辆在赶过去。
   *
   * <p>光统计「在管哪个人」不够：一辆车赶往伤员时发出的指令目标是<b>区域</b>
   * 而不是人，所以必须把「有队友正赶往这个区域」也算进来。
   */
  private final Map<EntityID, Integer> respondersByArea;

  /**
   * 连续多少个时间步「对着同一个目标毫无进展」就放弃它。
   *
   * <p>x 实测：上一局有 4 辆消防车从 t≈3 起锁定同一个够不到的伤员，一直挺到 t=270
   * 全局结束——它们每步都在重发同一个走不通的移动指令（路径规划完全不考虑路障），
   * 而 {@code isValidTarget} 始终为真，所以永远不会释放目标，也就永远不动。
   * 放弃之后会转去搜索自己的分区，至少还能干点有用的事。
   */
  private static final int NO_PROGRESS_LIMIT = 50;

  /**
   * 救护车「装不上」的快速放弃阈值（时间步）。
   *
   * <p>{@code DefaultExtActionTransport.calcRescue} 的装载条件是
   * 「<b>我模型里</b>伤员的位置 == 我的位置」，而仿真器比的是真值位置。
   * 模型一旦过期（人在别处，甚至早就被送进避难所），救护车就会站定不动、
   * 每步重发一次注定被拒的 {@code AKLoad}，<b>而它永远收不到那个拒绝</b>。
   *
   * <p>实测 kobe 20261014-211454：伤员 430399739 在 t=164 被挖出、t=169 被装载、
   * t=184 送进避难所 22670，可 <b>24 辆救护车从 t=183 一路对它发到 t=270，
   * 合计约 1188 次无效装载</b>（占全队预算的 15%）。每辆车要撞到
   * {@link #NO_PROGRESS_LIMIT} = 50 步才放弃——因为
   * {@link #hasProgress} 对救护车只看「我动没动」，站着不动永远算「没进展」。
   *
   * <p>这里给出确定性的判据：「同区 + 模型里 buriedness==0（我认为能装）+ 车上没人」
   * 连续 {@code LOAD_STUCK_LIMIT} 步，就是装不上，立刻放弃。合法的装载只需 1 步，
   * 所以 3 步足够安全；而等消防车挖掘时模型里 buriedness &gt; 0，不会误伤。
   */
  private static final int LOAD_STUCK_LIMIT = 3;

  /** 放弃一个目标后，隔多少个时间步才允许重新尝试它（避免立刻回头又卡住）。 */
  private static final int GIVE_UP_COOLDOWN = 60;

  /** 已经有这么多时间步没看到「消防车正在挖他」，就认为挖完了。见 {@link #probablyFreed}。 */
  private static final int DIG_STOP_GAP = 2;

  /** 「很可能已被救出」这个判断的有效期（时间步），过期就不再追。 */
  private static final int FREED_TTL = 30;

  /**
   * 消防车正在挖的人 -> 最后一次看到「正在挖他」的时间步。
   * 来源是 {@code MessageFireBrigade} 的 {@code ACTION_RESCUE}（目标字段就是被挖的人）。
   */
  private final Map<EntityID, Integer> diggingSeen;

  /**
   * 判断为「很可能已经被消防救出」的人 -> 判断时间步（仅救护车用）。
   *
   * <p>这是本工程最要命的一处信息缺口：救护车只认 {@code buriedness == 0} 的伤员，
   * 而掩埋度只能靠 {@code MessageCivilian} 得知——无线电每时间步总共 4000 字节、
   * 61 个智能体分，而且 {@code MessageCivilian} 是 LOW 优先级会被限流丢掉。
   * 结果实测一局里消防车挖出了 <b>28</b> 名不同的伤员，救护车却只发现并装载了
   * <b>8</b> 名：那 20 名已经躺在街上等人运的伤员，在救护车认知里"还是被埋的"。
   *
   * <p>判断依据来自消防车自己的报告：它在挖某人时会持续上报
   * {@code ACTION_RESCUE} 且目标就是那个人；一旦不再上报，多半就是挖完了。
   * 这个信号不需要额外通信，利用的是本来就在广播的消息。
   *
   * <p><b>注意</b>：这里只是让救护车「去接一个大概已经被挖出来的人」，
   * 救护车依然不参与挖掘——装载动作由 {@code DefaultExtActionTransport} 把关，
   * 它只在 {@code buriedness == 0} 时才真的装载。所以「消防先挖、救护后运」
   * 这条规则没有被破坏。
   */
  private final Map<EntityID, Integer> probablyFreed;

  /**
   * 听到「Help」喊话的平民 -> 最后一次听到他的时间步（仅消防车用来确定挖掘目标）。
   *
   * <p>这是本工程利用率最低的一块信息资产。被埋平民每步以
   * {@code civilian.help.probability = 0.1} 的概率通过<b>语音频道</b>
   * 喊一声「Help」（`SampleCivilian.say` → `sendSpeak`，半径 30m），
   * 一局日志里出现 518 次。发送者 ID 就在消息里，等于直接告诉我们
   * 「谁被埋了、而且就在我 30 米内」。
   *
   * <p>但 ADF 收到后只做了这件事（`StandardCommunicationModule:52-57`）：
   * <pre>
   * if ("Help".equalsIgnoreCase(voiceString) || ...) {
   *    messageManager.addHeardAgentHelpCount();   // 只加了个计数器
   *    continue;                                  // 发送者 ID 被丢弃
   * }
   * </pre>
   * 好消息是 {@code AgentInfo.getHeard()} 是公开的，所以不必改 adf-core，
   * 我们自己把原始 {@code AKSpeak} 读出来就行。
   *
   * <p>为什么这条线索关键：我们的世界模型里，一个平民的 {@code buriedness}
   * 往往是<b>过期的 0</b>（上次看见他时还站在街上），于是
   * {@code filterDigTargets} 会把他排除掉——人被埋在楼里，我们再也没看见过他。
   * 而「Help」喊话本身就是 {@code buriedness &gt; 0} 的直接证据，
   * 配上最后一次已知位置就能立刻去挖。
   */
  private final Map<EntityID, Integer> heardHelp;

  /** 听到喊「Ouch」的平民 -> 最后听到的时间步（仅救护车用来找需要转运的伤员）。 */
  private final Map<EntityID, Integer> heardOuch;

  /** 见 {@link #heardHelp}：平民大约每 10 步喊一次，所以 10 步内没再听到就作废。 */
  private static final int HEARD_HELP_TTL = 10;

  /** 「听到喊疼」的有效期。稍长一点，因为救护车可能要从远处赶来。 */
  private static final int HEARD_OUCH_TTL = 25;

  /**
   * 喊救命的平民<b>不在我们世界模型里</b>时，记下「我是在哪个区域听到的」。
   *
   * <p>实测这是最要命的一处漏网：一局里 39 名伤员中有 14 人<b>从头到尾没被挖出来</b>，
   * 其中 10 人「没有任何一个智能体的日志提到过他」——纯发现失败。而他们一直在喊救命，
   * 我们却用不上：{@code filterDigTargets} 是遍历
   * {@code worldInfo.getEntitiesOfType(CIVILIAN)} 的，<b>喊话的人如果根本不在模型里，
   * 这条线索就等于被丢掉了</b>。
   *
   * <p>喊话意味着他在 30 米内（语音频道半径）。所以这里记下听到喊话时自己所在的区域，
   * 下一步直接去那个区域附近的建筑里翻。
   */
  private final Map<EntityID, Integer> unknownShoutAt;

  /** 最近去查过哪些建筑 -> 时间步，避免在同一个建筑上反复原地打转。 */
  private final Map<EntityID, Integer> checkedBuildings;

  /** 同一个建筑多久之内不重复去查（时间步）。 */
  private static final int CHECKED_BUILDING_TTL = 40;

  /** 目标 ID -> 可以重新尝试它的时间步。 */
  private final Map<EntityID, Integer> giveUpUntil;

  /** 判定「有没有进展」用的上一步快照。 */
  private int lastBuriedness;
  private int lastPositionValue;
  private int noProgressSteps;

  /** 「同区 + 我认为能装 + 车上没人」已经连续发生了几步，见 {@link #LOAD_STUCK_LIMIT}。 */
  private int stuckLoadSteps;

  /** 诊断计数，见 {@link #logStats(boolean)}。 */
  private int statValid;
  private int statMine;
  private int statInSector;
  private int statIdleSteps;
  private boolean statPickedHere;

  /** 本步候选里「来不及救活」的人数，见 {@link #isFeasible}。用来验证这条排序键有没有真的生效。 */
  private int statInfeasible;

  // ---- 认领链路诊断（2026-10-17 加）----------------------------------------
  // 实测：三局 1300+ 次选中，Selected(responders=N) 的 N 全部是 0 —— 也就是说
  // respondersByHuman / respondersByArea 永远是空的，"把队伍摊开"那条排序键从没生效过。
  // 下面这组计数用来定位它为什么是空的：收到几条、几条解析不到实体、几条落到人、几条落到区块。
  /** 本步感知里听到的原始命令条数（{@code agentInfo.getHeard()} 的大小）。 */
  private int statHeard;
  /** 本步解析进 MessageManager 的消息条数（不分类别）。 */
  private int statRxAll;
  /** 本步收到的行动消息条数（消防车看 MessageFireBrigade，救护车看 MessageAmbulanceTeam）。 */
  private int statClaims;
  /** 其中目标字段为空的条数。 */
  private int statClaimNoTarget;
  /** 其中发信人是自己的条数（正常应为 0，模块层会滤掉）。 */
  private int statClaimSelf;
  /** 其中动作是 REST 的条数（没有实际作业对象，本来就要跳过）。 */
  private int statClaimRest;
  /** 其中目标 ID 在我们的世界模型里查不到的条数 —— 这就是"被静默丢掉"的那一批。 */
  private int statClaimUnknown;
  /** 其中落到人（Human）的条数。 */
  private int statClaimHuman;
  /** 其中落到区块（Area）的条数。 */
  private int statClaimArea;
  /** 本步候选里队友认领计数的最大值。只要它一直为 0，就说明认领链路整体没通。 */
  private int statRespMax;

  public SampleHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    logger = DefaultLogger.getLogger(agentInfo.me());
    this.transportRole = (ai.me().getStandardURN() == AMBULANCE_TEAM);
    this.respondersByHuman = new HashMap<>();
    this.respondersByArea = new HashMap<>();
    this.diggingSeen = new HashMap<>();
    this.probablyFreed = new HashMap<>();
    this.heardHelp = new HashMap<>();
    this.heardOuch = new HashMap<>();
    this.unknownShoutAt = new HashMap<>();
    this.checkedBuildings = new HashMap<>();
    this.giveUpUntil = new HashMap<>();
    this.lastBuriedness = Integer.MAX_VALUE;
    this.lastPositionValue = -1;
    this.noProgressSteps = 0;
    this.stuckLoadSteps = 0;
    this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering",
        "adf.impl.module.algorithm.KMeansClustering");
    registerModule(this.clustering);
  }


  @Override
  public HumanDetector updateInfo(MessageManager messageManager) {
    logger.debug("Time:" + agentInfo.getTime());
    super.updateInfo(messageManager);
    this.respondersByHuman.clear();
    this.respondersByArea.clear();
    this.statHeard = this.agentInfo.getHeard().size();
    this.statRxAll = messageManager.getReceivedMessageList().size();
    this.statClaims = 0;
    this.statClaimNoTarget = 0;
    this.statClaimSelf = 0;
    this.statClaimRest = 0;
    this.statClaimUnknown = 0;
    this.statClaimHuman = 0;
    this.statClaimArea = 0;
    if (this.transportRole) {
      for (CommunicationMessage message : messageManager
          .getReceivedMessageList(MessageAmbulanceTeam.class)) {
        MessageAmbulanceTeam mat = (MessageAmbulanceTeam) message;
        this.collectClaim(mat.getAgentID(), mat.getAction(), mat.getTargetID());
      }
    } else {
      for (CommunicationMessage message : messageManager
          .getReceivedMessageList(MessageFireBrigade.class)) {
        MessageFireBrigade mfb = (MessageFireBrigade) message;
        this.collectClaim(mfb.getAgentID(), mfb.getAction(), mfb.getTargetID());
      }
    }
    this.updateProbablyFreed(messageManager);
    this.updateHeardHelp();
    return this;
  }


  /**
   * 扫描本步听到的语音喊话，记下谁在喊「Help」。
   *
   * <p>ADF 已经把内容判成 Help/Ouch 了但没保留发送者，我们自己从
   * {@code AgentInfo.getHeard()} 里把原始 {@code AKSpeak}（频道 0 = 语音）取出来。
   */
  private void updateHeardHelp() {
    int now = this.agentInfo.getTime();
    for (Command heard : this.agentInfo.getHeard()) {
      if (!(heard instanceof AKSpeak)) {
        continue;
      }
      AKSpeak speak = (AKSpeak) heard;
      if (speak.getChannel() != 0) {
        continue;
      }
      byte[] content = speak.getContent();
      if (content == null || content.length == 0) {
        continue;
      }
      String text = new String(content, StandardCharsets.UTF_8);
      EntityID speaker = speak.getAgentID();
      if ("Help".equalsIgnoreCase(text)) {
        // 喊 Help ⇒ buriedness > 0 ⇒ 归消防车挖
        this.heardHelp.put(speaker, now);
        // 说话的人如果不在我们模型里（或位置未知），就记下「我在哪儿听到的」，
        // 下一步去那一带翻建筑——否则这条线索根本用不上
        if (!(this.worldInfo.getEntity(speaker) instanceof Human)
            || this.worldInfo.getPosition((Human) this.worldInfo
                .getEntity(speaker)) == null) {
          this.unknownShoutAt.put(this.agentInfo.getPosition(), now);
        }
      } else if ("Ouch".equalsIgnoreCase(text)) {
        // 喊 Ouch ⇒ damage > 0（受伤，自己走不动）⇒ 归救护车运
        this.heardOuch.put(speaker, now);
      }
    }
    expire(this.heardHelp, now, HEARD_HELP_TTL);
    expire(this.heardOuch, now, HEARD_OUCH_TTL);
    expire(this.unknownShoutAt, now, HEARD_HELP_TTL);
    expire(this.checkedBuildings, now, CHECKED_BUILDING_TTL);
  }


  private void expire(Map<EntityID, Integer> map, int now, int ttl) {
    Iterator<Map.Entry<EntityID, Integer>> it = map.entrySet().iterator();
    while (it.hasNext()) {
      if (now - it.next().getValue() > ttl) {
        it.remove();
      }
    }
  }


  /**
   * 救护车能不能去接这个人。
   *
   * <p>正常情况看世界模型里的掩埋度。但最大的窟窿就在这里：实测一局 39 名伤员里
   * 有 <b>11～14 人被挖出来之后一直躺在原地没人送</b>——救护车认知里他们还"被埋着"
   * （掩埋度是上次看见他们站在街上时留下的过期值），于是永远不去。
   *
   * <p>新增两条线索（都来自平民自己喊话，不需要任何额外通信）：
   * <ul>
   *   <li>听到他喊 <b>Ouch</b> ⇒ 他受伤了、自己走不动（`SampleCivilian` 里
   *       Ouch 的条件是 damage &gt; 0），正需要救护车。一局里喊了 795 次。</li>
   *   <li>但如果他最近还喊过 <b>Help</b>，说明仍被埋着（Help 的条件是
   *       buriedness &gt; 0），那就还是消防车的事，救护车不去白跑。</li>
   * </ul>
   * 二者结合正好把"挖出来了但还没被送走"的人筛出来。
   */
  private boolean isRescuableByShout(Human human) {
    if (!this.transportRole || !this.heardOuch.containsKey(human.getID())) {
      return false;
    }
    return !this.heardHelp.containsKey(human.getID());
  }


  /**
   * 有没有「听到了喊救命、但不知道人在哪」的情况。
   * 有的话就去听到喊话的地点附近翻建筑。
   */
  private EntityID pickBuildingNearShout() {
    if (this.transportRole || this.unknownShoutAt.isEmpty()) {
      return null;
    }
    // 取「最近一次」听到喊话的地点。不能随便取第一个 key：
    // HashMap 的遍历顺序不确定，会让同一份代码每局行为不同，反而给跑批引入噪声。
    EntityID shoutAt = null;
    int latest = Integer.MIN_VALUE;
    for (Map.Entry<EntityID, Integer> entry : this.unknownShoutAt
        .entrySet()) {
      if (entry.getValue() > latest) {
        latest = entry.getValue();
        shoutAt = entry.getKey();
      }
    }
    if (shoutAt == null) {
      return null;
    }
    double bestDistance = Double.MAX_VALUE;
    EntityID best = null;
    for (StandardEntity entity : this.worldInfo
        .getEntitiesOfType(StandardEntityURN.BUILDING,
            StandardEntityURN.GAS_STATION, StandardEntityURN.REFUGE)) {
      if (this.checkedBuildings.containsKey(entity.getID())) {
        continue;
      }
      double distance = this.worldInfo.getDistance(shoutAt, entity.getID());
      if (distance < bestDistance) {
        bestDistance = distance;
        best = entity.getID();
      }
    }
    if (best != null) {
      this.checkedBuildings.put(best, this.agentInfo.getTime());
    }
    return best;
  }


  /** 最近听到这个人喊过救命（意味着他此刻被埋着，且我们在他 30 米内）。 */
  private boolean isHeardHelp(EntityID humanID) {
    Integer at = this.heardHelp.get(humanID);
    return at != null
        && this.agentInfo.getTime() - at <= HEARD_HELP_TTL;
  }


  /**
   * 从消防车的报告里推断「谁大概已经被挖出来了」。
   * 两件事：把正在被挖的人记下来；把「不再被挖」的人转成「很可能已获救」。
   */
  private void updateProbablyFreed(MessageManager messageManager) {
    int now = this.agentInfo.getTime();
    for (CommunicationMessage message : messageManager
        .getReceivedMessageList(MessageFireBrigade.class)) {
      MessageFireBrigade mfb = (MessageFireBrigade) message;
      EntityID targetID = mfb.getTargetID();
      if (mfb.getAction() == MessageFireBrigade.ACTION_RESCUE
          && targetID != null
          && this.worldInfo.getEntity(targetID) instanceof Human) {
        this.diggingSeen.put(targetID, now);
      }
    }

    Iterator<Map.Entry<EntityID, Integer>> digging = this.diggingSeen
        .entrySet().iterator();
    while (digging.hasNext()) {
      Map.Entry<EntityID, Integer> entry = digging.next();
      if (now - entry.getValue() > DIG_STOP_GAP) {
        this.probablyFreed.put(entry.getKey(), now);
        digging.remove();
      }
    }

    Iterator<Map.Entry<EntityID, Integer>> freed = this.probablyFreed
        .entrySet().iterator();
    while (freed.hasNext()) {
      if (now - freed.next().getValue() > FREED_TTL) {
        freed.remove();
      }
    }
  }


  /** 这个人是否「很可能已被消防挖出」（仅救护车据此提前赶过去接人）。 */
  private boolean isProbablyFreed(EntityID humanID) {
    return this.transportRole && this.probablyFreed.containsKey(humanID);
  }


  private void collectClaim(EntityID agentID, int action, EntityID targetID) {
    this.statClaims++;
    if (targetID == null || agentID == null) {
      this.statClaimNoTarget++;
      return;
    }
    if (agentID.getValue() == this.agentInfo.getID().getValue()) {
      this.statClaimSelf++;
      return;
    }
    // REST 在救护车和消防车两种消息里都是 0，先排除掉
    if (action == MessageAmbulanceTeam.ACTION_REST) {
      this.statClaimRest++;
      return;
    }
    StandardEntity entity = this.worldInfo.getEntity(targetID);
    if (entity instanceof Human) {
      // 正对着某个人作业（装载 / 挖掘）
      this.respondersByHuman.merge(targetID, 1, Integer::sum);
      this.statClaimHuman++;
    } else if (entity instanceof Area) {
      // 正赶往某个区域（移动指令）
      this.respondersByArea.merge(targetID, 1, Integer::sum);
      this.statClaimArea++;
    } else {
      // 关键诊断：目标在我们的世界模型里查不到，这条认领被静默丢掉了。
      // 实测 responders 恒为 0，这里就是最可疑的那一处。
      this.statClaimUnknown++;
    }
  }


  /** 已经有几辆同类型队友在管这个人（人本身 + 他所在区域两条线索相加）。 */
  private int responderCount(Human human) {
    int count = this.respondersByHuman.getOrDefault(human.getID(), 0);
    if (human.isPositionDefined()) {
      count += this.respondersByArea.getOrDefault(human.getPosition(), 0);
    }
    return count;
  }


  @Override
  public HumanDetector calc() {
    this.statValid = 0;
    this.statMine = 0;
    this.statInfeasible = 0;
    this.statRespMax = 0;
    Human transportHuman = this.agentInfo.someoneOnBoard();
    if (transportHuman != null) {
      logger.debug("someoneOnBoard:" + transportHuman);
      this.result = transportHuman.getID();
      this.logStats(true);
      return this;
    }
    if (this.result != null) {
      StandardEntity current = this.worldInfo.getEntity(this.result);
      if (!(current instanceof Human)) {
        // 目标是「去查看的建筑」（见 pickBuildingNearShout）。
        // 只在喊声记忆过期后放弃；不因为「出现了已知目标」而放弃——
        // 理由见 calcTarget 开头：近处的救命声应当压过远处的已知目标。
        if (this.unknownShoutAt.isEmpty()) {
          this.dropTarget();
        }
      } else {
        Human target = (Human) current;
        if (!isValidTarget(target)) {
          logger.debug("Invalid Human:" + target + " ==>reset target");
          this.dropTarget();
        } else if (isStuckLoading(target)) {
          logger.debug("Stuck loading " + target + " for " + this.stuckLoadSteps
              + " steps ==> give up");
          this.giveUpUntil.put(this.result,
              this.agentInfo.getTime() + GIVE_UP_COOLDOWN);
          this.dropTarget();
        } else if (!this.hasProgress(target)) {
          this.noProgressSteps++;
          if (this.noProgressSteps >= NO_PROGRESS_LIMIT) {
            logger.debug("No progress on " + target + " for "
                + this.noProgressSteps + " steps ==> give up");
            this.giveUpUntil.put(this.result,
                this.agentInfo.getTime() + GIVE_UP_COOLDOWN);
            this.dropTarget();
          }
        } else {
          // 看门狗统计的是连续停滞步数；移动或挖掘有进展后必须清零，
          // 否则零散的停滞会累积并让正在正常工作的车辆过早放弃目标。
          this.noProgressSteps = 0;
        }
      }
    }
    if (this.result == null) {
      this.result = calcTarget();
    }
    // pick=1 表示「本步手上有目标」，这才是利用率口径
    this.statPickedHere = this.result != null;
    if (this.result == null) {
      this.statIdleSteps++;
    }
    this.logStats(false);
    return this;
  }


  /**
   * 输出一行诊断，用来回答一个关键分叉：消防车/救护车长时间没有目标，
   * 究竟是「世界模型里根本没有可救的人」（发现能力问题），
   * 还是「有，但被认领/分区/名次等过滤条件筛掉了」（调度代码问题）。
   *
   * <ul>
   *   <li>{@code valid} 角色过滤后的候选数（世界模型里有多少可救的人）</li>
   *   <li>{@code mine} 再经认领、放弃冷却、分区/名次过滤后还剩多少</li>
   *   <li>{@code infeasible} 其中被 {@link #isFeasible} 判为「来不及救活」的有几个。
   *       它恒为 0 就说明那条排序键没生效；恒等于 {@code mine} 就说明它把候选全压平了。</li>
   *   <li>{@code claims}/{@code cNoTgt}/{@code cSelf}/{@code cRest}/{@code cUnknown}/
   *       {@code cHuman}/{@code cArea} —— 认领链路诊断：本步收到几条队友行动消息，
   *       以及它们分别被哪一道判断拦下。{@code cHuman}+{@code cArea} 应当接近
   *       {@code claims}-{@code cRest}；若差得远，差额就在 {@code cUnknown}（目标查不到实体）。</li>
   *   <li>{@code respMax} 候选里队友认领计数的最大值。<b>它恒为 0 就说明认领链路整体没通</b>，
   *       那条"把队伍摊开"的排序键等于不存在。</li>
   *   <li>{@code pick} 本次是否拿到了目标</li>
   *   <li>{@code idle} 本局累计「一个候选都没有」的时间步数</li>
   * </ul>
   */
  private void logStats(boolean onboard) {
    logger.debug("STATS t=" + this.agentInfo.getTime()
        + " role=" + (this.transportRole ? "AT" : "FB")
        + " onboard=" + (onboard ? 1 : 0)
        + " valid=" + this.statValid
        + " mine=" + this.statMine
        + " inSector=" + this.statInSector
        + " infeasible=" + this.statInfeasible
        + " heard=" + this.statHeard
        + " rxAll=" + this.statRxAll
        + " claims=" + this.statClaims
        + " cNoTgt=" + this.statClaimNoTarget
        + " cSelf=" + this.statClaimSelf
        + " cRest=" + this.statClaimRest
        + " cUnknown=" + this.statClaimUnknown
        + " cHuman=" + this.statClaimHuman
        + " cArea=" + this.statClaimArea
        + " respMax=" + this.statRespMax
        + " heardHelp=" + this.heardHelp.size()
        + " heardOuch=" + this.heardOuch.size()
        + " unknownShout=" + this.unknownShoutAt.size()
        + " pick=" + (this.statPickedHere ? 1 : 0)
        + " idle=" + this.statIdleSteps);
  }


  private EntityID calcTarget() {
    // 0) 近处有人在喊救命、但我们不知道他在哪 ⇒ 优先就地翻建筑。
    //
    // 实测：消防车有多达 14~23% 的时间步听到「不在自己模型里的伤员」喊救命，
    // 也就是它已经站在被埋者 30 米内，却始终没去挖——因为原来这条线索只在
    // 【没有任何已知目标】时才启用，而消防车 69~75% 的时间手上有目标，把它饿死了。
    //
    // 为什么近处线索该压过远处的已知目标：救援是一场 70~100 步的赛跑
    // （damage 每步扣 100+ 且在恶化），而挖出一名被埋者要 60 步。所以
    // 「零路程 + 刚听到的线索」比「还要跑很远、再挖 60 步」更可能救活。
    // 例外：正在目标位置施工（挖掘中）时不打断，否则已投入的挖掘进度就白费了。
    if (!this.transportRole && !this.unknownShoutAt.isEmpty()
        && !this.isWorkingOnTarget()) {
      EntityID nearby = this.pickBuildingNearShout();
      if (nearby != null) {
        logger.debug("Heard help nearby but victim unknown; checking "
            + nearby);
        return nearby;
      }
    }

    List<Human> candidates = this.transportRole
        ? filterTransportTargets(this.worldInfo.getEntitiesOfType(CIVILIAN))
        : filterDigTargets(this.worldInfo.getEntitiesOfType(CIVILIAN,
            FIRE_BRIGADE, POLICE_FORCE, AMBULANCE_TEAM));

    this.statValid = candidates.size();

    // 只有「刚放弃还在冷却期内」是硬性排除；其余一律用软排序。
    // 早先版本把「已被队友认领」也做成硬排除，再加上「只允许最近的 2 辆」，
    // 结果埋点显示消防车 36.8% 的时间步是「有候选却被筛掉」而闲置。
    List<Human> usable = new ArrayList<>();
    for (Human human : candidates) {
      if (this.isGivenUp(human)) {
        continue;
      }
      if (!this.isFeasible(human)) {
        this.statInfeasible++;
      }
      usable.add(human);
    }
    this.statMine = usable.size();
    // 诊断：候选里最大的队友认领计数。恒为 0 就说明认领链路整体没通。
    for (Human human : usable) {
      int responders = this.responderCount(human);
      if (responders > this.statRespMax) {
        this.statRespMax = responders;
      }
    }

    logger.debug("Cluster:" + getOwnClusterIndex() + " Targets:" + usable);
    if (!usable.isEmpty()) {
      return this.pickBest(usable);
    }
    return null;
  }


  /**
   * 救护车是不是「站在目标身上却怎么也装不上」。
   *
   * <p>三个条件同时成立才算：同处一区、我的模型认为他已挖出（buriedness == 0）、
   * 车上还是空的。连续 {@link #LOAD_STUCK_LIMIT} 步都这样，就说明模型位置是错的，
   * 再站着发 {@code AKLoad} 也只是白费——直接放弃并锁 {@link #GIVE_UP_COOLDOWN} 步。
   */
  private boolean isStuckLoading(Human target) {
    if (!this.transportRole) {
      return false;
    }
    boolean sameArea = target.isPositionDefined()
        && this.agentInfo.getPosition().equals(target.getPosition());
    boolean thinkLoadable = target.isBuriednessDefined()
        && target.getBuriedness() == 0;
    boolean emptyHanded = this.agentInfo.someoneOnBoard() == null;
    if (sameArea && thinkLoadable && emptyHanded) {
      this.stuckLoadSteps++;
    } else {
      this.stuckLoadSteps = 0;
    }
    return this.stuckLoadSteps >= LOAD_STUCK_LIMIT;
  }


  /** 是否已经在目标位置施工（挖掘中）——此时不该被新线索打断。 */
  private boolean isWorkingOnTarget() {
    if (this.result == null) {
      return false;
    }
    StandardEntity target = this.worldInfo.getEntity(this.result);
    if (!(target instanceof Human)) {
      return false;
    }
    return ((Human) target).isPositionDefined()
        && this.agentInfo.getPosition().equals(((Human) target).getPosition());
  }


  /**
   * 实测的平均移动速度（毫米/步）。
   *
   * <p>kobe 20261015-144545 一局，全部消防/救护/警车共 18623 个"有位移的步"，
   * 位移总和 380376656 mm ⇒ 平均每步 <b>20425 mm</b>（单步最大 55382 mm）。
   * {@code worldInfo.getDistance()} 给的是直线距离，而实际要走路网、会绕远，
   * 两个误差方向相反，这里直接用实测均值，不做修正。
   */
  private static final int MOVE_SPEED_MM_PER_STEP = 20425;

  /**
   * 这个人还来不来得及救。
   *
   * <p>三个实测事实把这条排序键的必要性钉住了：
   * <ol>
   *   <li>kobe 20261015-144545：39 名伤员里 14 人从未被挖出即死亡。<b>其中 7 人不是没人管，
   *       而是「已经选了目标、也真的挖了，但没挖完人就死了」</b>——首挖时刻分别是
   *       t=154/157/161/179/181/201/232，而死线是 t=173/211/173/241/190/211/240。
   *       最刺眼的是 {1501674478}：挖了 62 步、埋压度从 60 只剩 <b>1</b>，t=242 死亡。</li>
   *   <li>同局 1579 条挖掘指令只指向 <b>32 个</b>伤员：30 辆消防车整局平均只发出 53 条挖掘指令，
   *       <b>相当于每辆车整局只挖透一个人</b>，八成时间花在赶路上。所以"赶路时间"必须算进去，
   *       否则判据会把「还来得及」误判成「来不及」、反之亦然。</li>
   *   <li>本服务端的避难所床位只降 damage、<b>HP 永不恢复</b>，伤员在被埋期间掉的每一滴血
   *       都是永久性的分数损失。实测一局里这一项占全部分数损失的 <b>28.4%</b>。
   *       把挖掘时间花在注定救不活的人身上，等于同时放弃了「他」和「本可以救活的人」。</li>
   * </ol>
   *
   * <p>判据只用世界模型里已经有的几个数，不引入任何新通信：
   * <pre>
   *   预计还能撑 = HP / 单步伤害                    多少步之后血量归零
   *   还需步数   = 埋压度 + 直线距离 / 移动速度      挖出来的步数 + 赶过去的步数
   *   来得及     ⇔ 预计还能撑 &gt;= 还需步数
   * </pre>
   * 用的都是「我最后一次看到的值」，可能过期——过期只会让判断偏保守。
   *
   * <p><b>这只是排序键，不是过滤器。</b>来不及的人排到后面去，但全队都没别的活时照样会去挖，
   * 不会重演早先「有候选却被筛掉、智能体闲置」的老问题（那时硬过滤让消防车
   * 36.8% 的时间步有活不干）。它也只影响<b>新目标</b>的挑选：{@link #calcTarget} 只在
   * 手上没目标时才被调用，正在挖的车不会被中途改派，不存在"挖到一半被叫走"。
   *
   * <p>救护车不参与挖掘，这条与它无关，直接返回 true。
   */
  private boolean isFeasible(Human human) {
    if (this.transportRole) {
      return true;
    }
    if (!human.isHPDefined() || !human.isDamageDefined()
        || !human.isBuriednessDefined()) {
      return true;                    // 数据不全就不下判断，按「来得及」处理
    }
    int damage = human.getDamage();
    if (damage <= 0) {
      return true;                    // 不流血 ⇒ 没有死线压力
    }
    int walkSteps = this.worldInfo
        .getDistance(this.agentInfo.me(), human) / MOVE_SPEED_MM_PER_STEP;
    return human.getHP() / damage >= human.getBuriedness() + walkSteps;
  }


  /**
   * 在候选里挑一个最好的。排序键依次是：
   * <ol>
   *   <li><b>已经被几辆队友盯上</b>（少者优先）——这一条负责把人摊开，
   *       避免全队扑同一个人。它取代了早先的「认领即排除」硬过滤：
   *       硬过滤会让消防车 36.8% 的时间步有活却不干。</li>
   *   <li><b>是否还来得及救活</b>（来得及者优先）——见 {@link #isFeasible}。
   *       放在「队友数量」之后是有意的：开局时所有人的队友计数都是 0，
   *       这一段由它决定「先去谁那儿」；等有人开工了，仍由队友数负责把队伍摊开。</li>
   *   <li><b>是否在自己的分区内</b>（是者优先）——软性的分区偏好，
   *       保留 CSU_Yunlu 那种「就近作业」的意图，但不再阻止跨区帮忙。</li>
   *   <li><b>距离</b>（近者优先）。</li>
   * </ol>
   */
  private EntityID pickBest(List<Human> candidates) {
    final Collection<StandardEntity> clusterEntities = this.getOwnClusterEntities();
    candidates.sort((a, b) -> {
      int byResponders = Integer.compare(this.responderCount(a),
          this.responderCount(b));
      if (byResponders != 0) {
        return byResponders;
      }
      int byFeasible = Boolean.compare(this.isFeasible(b), this.isFeasible(a));
      if (byFeasible != 0) {
        return byFeasible;
      }
      int bySector = Boolean.compare(isInSector(b, clusterEntities),
          isInSector(a, clusterEntities));
      if (bySector != 0) {
        return bySector;
      }
      int byDistance = Integer.compare(
          this.worldInfo.getDistance(this.agentInfo.me(), a),
          this.worldInfo.getDistance(this.agentInfo.me(), b));
      if (byDistance != 0) {
        return byDistance;
      }
      return Integer.compare(a.getID().getValue(), b.getID().getValue());
    });
    Human selected = candidates.get(0);
    logger.debug("Selected(responders=" + this.responderCount(selected)
        + "):" + selected);
    this.noProgressSteps = 0;
    this.stuckLoadSteps = 0;
    return selected.getID();
  }


  private boolean isInSector(Human human,
      Collection<StandardEntity> clusterEntities) {
    if (clusterEntities == null || !human.isPositionDefined()) {
      return false;
    }
    StandardEntity position = this.worldInfo.getPosition(human);
    return position != null && clusterEntities.contains(position);
  }


  /** 清掉当前目标，并把「有没有进展」的计数器复位。 */
  private void dropTarget() {
    this.result = null;
    this.noProgressSteps = 0;
    this.stuckLoadSteps = 0;
    this.lastBuriedness = Integer.MAX_VALUE;
    this.lastPositionValue = this.agentInfo.getPosition().getValue();
  }


  /**
   * 相对目标有没有进展。两种情况都算进展：
   * <ul>
   *   <li>自己在移动（还在赶往目标的路上）——这一条对两种角色都成立，
   *       少了它就会把「正在长途跋涉」误判成「卡死」；</li>
   *   <li>目标的掩埋度在下降（消防车挖掘中；挖掘时人是站着不动的，
   *       所以只看位移会把正在挖的人误判成卡死）。</li>
   * </ul>
   * 两者都持续不成立，才说明真的卡住了（例如目标在路障后面，怎么走都过不去）。
   */
  private boolean hasProgress(Human target) {
    int position = this.agentInfo.getPosition().getValue();
    boolean moved = position != this.lastPositionValue;
    this.lastPositionValue = position;

    if (this.transportRole) {
      return moved;
    }
    int buriedness = target.isBuriednessDefined() ? target.getBuriedness() : 0;
    boolean dug = buriedness < this.lastBuriedness;
    this.lastBuriedness = buriedness;
    // 还在断续听到他喊救命 ⇒ 他仍被埋着但还没死，挖下去有意义。
    // 少了这一条会出问题：如果我们模型里的掩埋度是过期的 0，
    // 那「掩埋度下降」永远不成立，挖满 50 步就会被看门狗误判为卡死而放弃。
    boolean stillAliveUnderRubble = isHeardHelp(target.getID());
    return moved || dug || stillAliveUnderRubble;
  }


  private boolean isGivenUp(Human human) {
    Integer until = this.giveUpUntil.get(human.getID());
    return until != null && until > this.agentInfo.getTime();
  }


  private int getOwnClusterIndex() {
    return this.clustering.getClusterIndex(this.agentInfo.getID());
  }


  /** 本智能体所属分区的实体集合；分区信息不可用时返回 null。 */
  private Collection<StandardEntity> getOwnClusterEntities() {
    Collection<StandardEntity> entities = this.clustering
        .getClusterEntities(getOwnClusterIndex());
    if (entities == null || entities.isEmpty()) {
      return null;
    }
    return entities;
  }


  @Override
  public EntityID getTarget() {
    return this.result;
  }


  /**
   * 救护车目标：已被挖出、仍受伤、且能被装载的平民。
   * 被埋人员（buriedness &gt; 0）一律排除，留给消防车。
   */
  private List<Human>
      filterTransportTargets(Collection<? extends StandardEntity> list) {
    List<Human> rescueTargets = new ArrayList<>();
    for (StandardEntity next : list) {
      if (isValidTarget(next)) {
        rescueTargets.add((Human) next);
      }
    }
    return rescueTargets;
  }


  /**
   * 消防车目标：仍被掩埋、需要挖出的人（含平民与其他救援队员）。
   */
  private List<Human> filterDigTargets(Collection<? extends StandardEntity> list) {
    List<Human> rescueTargets = new ArrayList<>();
    for (StandardEntity next : list) {
      if (!(next instanceof Human)) {
        continue;
      }
      Human human = (Human) next;
      if ((isBuried(human) || isHeardHelp(human.getID()))
          && human.isHPDefined() && human.getHP() > 0
          && hasReachablePosition(human)
          && !human.getID().equals(this.agentInfo.getID())) {
        rescueTargets.add(human);
      }
    }
    return rescueTargets;
  }


  private boolean isBuried(StandardEntity entity) {
    Human human = (Human) entity;
    return human.isBuriednessDefined() && human.getBuriedness() > 0;
  }


  /**
   * 本智能体是否应当以该人员为目标（不区分角色，调用方已按角色取过候选集）。
   */
  private boolean isValidTarget(StandardEntity entity) {
    if (entity == null || !(entity instanceof Human)) {
      return false;
    }
    Human target = (Human) entity;
    if (!target.isHPDefined() || target.getHP() == 0) {
      return false;
    }
    if (!target.isPositionDefined() || !target.isBuriednessDefined()) {
      return false;
    }
    if (!hasReachablePosition(target)) {
      return false;
    }

    if (this.transportRole) {
      // 救护车：只接未被掩埋、仍受伤的平民
      if (target.getStandardURN() != CIVILIAN) {
        return false;
      }
      if (!target.isDamageDefined() || target.getDamage() == 0) {
        return false;
      }
      // 核心规则：被埋人员不归救护车，必须等消防先挖出来。
      // 例外有两个，都是为了接「已经挖出来但没人送」的伤员：
      //   1) 消防车已经停止挖他（probablyFreed）
      //   2) 听到他喊 Ouch 而最近没喊 Help（说明已挖出但在受伤）
      // 真正能不能装载仍由 DefaultExtActionTransport 按 buriedness 把关，
      // 所以救护车绝不会去挖——只是提前赶过去。
      return target.getBuriedness() == 0
          || this.isProbablyFreed(target.getID())
          || this.isRescuableByShout(target);
    }

    // 消防车：挖被掩埋的人；
    // 另外，只要最近听到他喊「Help」，就说明他确实被埋着
    // （喊话本身就是 buriedness > 0 的证据），哪怕我们模型里的掩埋度
    // 是上次看到他还站在街上时留下的过期 0。
    return isBuried(target) || isHeardHelp(target.getID());
  }


  private boolean hasReachablePosition(Human human) {
    StandardEntity position = this.worldInfo.getPosition(human);
    if (position == null) {
      return false;
    }
    StandardEntityURN positionURN = position.getStandardURN();
    return positionURN != REFUGE && positionURN != AMBULANCE_TEAM;
  }


  private class DistanceSorter implements Comparator<StandardEntity> {

    private StandardEntity reference;
    private WorldInfo worldInfo;

    DistanceSorter(WorldInfo wi, StandardEntity reference) {
      this.reference = reference;
      this.worldInfo = wi;
    }


    public int compare(StandardEntity a, StandardEntity b) {
      int d1 = this.worldInfo.getDistance(this.reference, a);
      int d2 = this.worldInfo.getDistance(this.reference, b);
      return d1 - d2;
    }
  }
}
