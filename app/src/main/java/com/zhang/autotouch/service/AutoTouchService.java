package com.zheng.autotouch.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.annotation.RequiresApi;
import com.zheng.autotouch.TouchEventManager;
import com.zheng.autotouch.bean.TouchEvent;
import com.zheng.autotouch.bean.TouchPoint;
import com.zheng.autotouch.utils.ToastUtil;
import java.util.List;
import java.util.Random;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * 无障碍服务-自动点击
 * @date 2019/9/6 16:23
 */
@RequiresApi(api = Build.VERSION_CODES.N)
public class AutoTouchService extends AccessibilityService {
  private static final String TAG = "AT-Fast";
  private static final long FAST_POLL_MS = 100L;
  private static final long TAP_DURATION_MS = 20L;
  private static final int BURST_COUNT = 2;
  private static final long BURST_GAP_MS = 30L;
  private static final long HEART_BEAT_LOG_MS = 1000L;
  private static final long NO_SIGNAL_FORCE_REFRESH_MS = 6000L;
  private static final long BACK_WAIT_MIN_MS = 320L;
  private static final long BACK_WAIT_MAX_MS = 760L;
  private static final long REENTER_RETRY_MIN_MS = 240L;
  private static final long REENTER_RETRY_MAX_MS = 680L;
  private static final long REENTER_SEARCH_AFTER_SWIPE_DELAY_MIN_MS = 1000L;
  private static final long REENTER_SEARCH_AFTER_SWIPE_DELAY_MAX_MS = 1500L;
  private static final int REENTER_SWIPE_UP_PX_MIN = 220;
  private static final int REENTER_SWIPE_UP_PX_MAX = 520;
  private static final int REENTER_SWIPE_PX_MIN = 400;
  private static final int REENTER_SWIPE_PX_MAX = 700;
  private static final int REENTER_MAX_SWIPE_SEARCH_COUNT = 2;
  private static final int POINT_DISABLED_CONFIRM_COUNT = 2;
  private static final int SOLD_OUT_CONFIRM_COUNT = 2;
  private static final long SOLD_OUT_TOAST_VALID_MS = 1200L;
  private static final long SOLD_OUT_BACKOFF_MS = 400L;
  private static final long REFRESH_COOLDOWN_MS = 1200L;
  private static final int REENTER_MAX_RETRY = 8;

  private static final String[] BUY_TEXTS = new String[] {"立即购买", "去购买"};
  private static final String[] SOLD_OUT_TEXTS =
      new String[] {"已售罄", "售罄", "库存不足", "缺货"};
  private static final String[] SUCCESS_TEXTS =
      new String[] {"提交订单", "确认订单", "去支付", "支付订单", "订单确认"};
  private static final String[] REENTER_HINT_TEXTS =
      new String[] {"马踏紫烟", "丙午马年", "生肖", "经典版", "礼盒装"};

  /**
   * 有自定义关键词时，重进仅使用该关键词；找不到就直接坐标兜底，不走通用关键词。
   */
  private static final boolean STRICT_REENTER_WITH_CUSTOM_KEYWORD = true;

  private static final int STATE_IDLE = 0;
  private static final int STATE_WATCH_BUY = 1;
  private static final int STATE_SOLD_OUT_BACKOFF = 2;
  private static final int STATE_REENTERING = 3;

  /**
   * 已售罄处理策略
   * 1: 返回上一页并自动重进（推荐）
   * 2: 停留当前页，仅持续轮询（不执行返回）
   */
  private static final int SOLD_OUT_MODE_BACK_REENTER = 1;
  private static final int SOLD_OUT_MODE_STAY_PAGE = 2;
  private static final int SOLD_OUT_MODE = SOLD_OUT_MODE_BACK_REENTER;
  private static final String DEFAULT_TARGET_PACKAGE = "com.moutai.mall";
  private static final int SWIPE_PHASE_NONE = 0;
  private static final int SWIPE_PHASE_AFTER_UP = 1;
  private static final int SWIPE_PHASE_AFTER_DOWN = 2;

  private TouchPoint autoTouchPoint;
  private final Handler handler = new Handler(Looper.getMainLooper());

  private int currentState = STATE_IDLE;
  private int soldOutCount;
  private long soldOutToastAt;
  private long soldOutBackoffUntil;
  private long lastRefreshAt;
  private int reenterRetryCount;
  private long lastBurstAt;
  private long lastHeartBeatAt;
  private long lastSignalAt;
  private long loopRound;
  private int pointDisabledCount;
  private int noSignalTimeoutCount;
  private int reenterSwipeSearchCount;
  private int reenterSwipePhase;
  private boolean lastReenterActionWasSwipe;
  private long lastReenterSwipeDelayMs;
  private String lastWindowPackage = "unknown";
  private String targetPackage = DEFAULT_TARGET_PACKAGE;
  private final Random random = new Random();

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this);
    }
    Log.i(TAG, "onServiceConnected: accessibility ready");
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  public void onReciverTouchEvent(TouchEvent event) {
    TouchEventManager.getInstance().setTouchAction(event.getAction());
    Log.i(TAG, "touchEvent=" + actionName(event.getAction()));
    switch (event.getAction()) {
      case TouchEvent.ACTION_START:
        autoTouchPoint = event.getTouchPoint();
        startFastMode();
        break;
      case TouchEvent.ACTION_CONTINUE:
        if (autoTouchPoint != null) {
          startFastMode();
        }
        break;
      case TouchEvent.ACTION_PAUSE:
        stopFastMode(false);
        break;
      case TouchEvent.ACTION_STOP:
        stopFastMode(true);
        autoTouchPoint = null;
        break;
      default:
        break;
    }
  }

  private void startFastMode() {
    if (autoTouchPoint == null) {
      Log.w(TAG, "startFastMode ignored: point is null");
      return;
    }
    currentState = STATE_WATCH_BUY;
    soldOutCount = 0;
    pointDisabledCount = 0;
    noSignalTimeoutCount = 0;
    reenterSwipeSearchCount = 0;
    reenterSwipePhase = SWIPE_PHASE_NONE;
    lastReenterActionWasSwipe = false;
    lastReenterSwipeDelayMs = 0L;
    soldOutBackoffUntil = 0L;
    loopRound = 0;
    lastHeartBeatAt = 0L;
    lastSignalAt = System.currentTimeMillis();
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root != null && root.getPackageName() != null) {
      targetPackage = root.getPackageName().toString();
    } else {
      targetPackage = DEFAULT_TARGET_PACKAGE;
    }
    handler.removeCallbacks(fastLoopRunnable);
    handler.removeCallbacks(reenterRunnable);
    Log.i(TAG,
        "fastMode START point=(" + autoTouchPoint.getX() + "," + autoTouchPoint.getY()
            + ") keyword=" + autoTouchPoint.getName() + " soldOutMode=" + soldOutModeName()
            + " targetPkg=" + targetPackage);
    handler.post(fastLoopRunnable);
  }

  private void stopFastMode(boolean clearRefreshState) {
    handler.removeCallbacks(fastLoopRunnable);
    handler.removeCallbacks(reenterRunnable);
    currentState = STATE_IDLE;
    soldOutCount = 0;
    soldOutBackoffUntil = 0L;
    if (clearRefreshState) {
      reenterRetryCount = 0;
    }
    Log.i(TAG, "fastMode STOP clearRefreshState=" + clearRefreshState);
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event == null) {
      return;
    }
    if (containsAny(event.getText(), SOLD_OUT_TEXTS)) {
      soldOutToastAt = System.currentTimeMillis();
      soldOutCount = Math.max(soldOutCount, 1);
      Log.i(TAG, "event sold-out text detected -> trigger fast loop");
      scheduleFastLoop(0L);
      return;
    }
    int eventType = event.getEventType();
    if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        || eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        || containsAny(event.getText(), BUY_TEXTS)) {
      scheduleFastLoop(0L);
    }
  }

  @Override
  public void onInterrupt() {}

  @Override
  public void onDestroy() {
    super.onDestroy();
    if (EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().unregister(this);
    }
    handler.removeCallbacksAndMessages(null);
  }

  private final Runnable fastLoopRunnable = new Runnable() {
    @Override
    public void run() {
      if (autoTouchPoint == null || !TouchEventManager.getInstance().isTouching()) {
        return;
      }

      long now = System.currentTimeMillis();
      loopRound++;
      logHeartBeat(now);
      if (now < soldOutBackoffUntil) {
        scheduleFastLoop(FAST_POLL_MS);
        return;
      }

      AccessibilityNodeInfo root = getRootInActiveWindow();
      if (root == null) {
        scheduleFastLoop(FAST_POLL_MS);
        return;
      }
      if (root.getPackageName() != null) {
        lastWindowPackage = root.getPackageName().toString();
      }

      if (containsText(root, SUCCESS_TEXTS)) {
        lastSignalAt = now;
        noSignalTimeoutCount = 0;
        Log.i(TAG, "success page detected -> auto stop");
        ToastUtil.show("已进入下单页，自动停止");
        TouchEvent.postStopAction();
        return;
      }

      boolean soldOut = isSoldOut(root, now);
      if (soldOut) {
        lastSignalAt = now;
        noSignalTimeoutCount = 0;
        soldOutCount++;
        if (soldOutCount == 1) {
          Log.i(TAG, "sold-out candidate detected");
        }
        if (soldOutCount >= SOLD_OUT_CONFIRM_COUNT) {
          soldOutCount = 0;
          onSoldOutConfirmed(now);
        }
        scheduleFastLoop(FAST_POLL_MS);
        return;
      }
      soldOutCount = 0;

      // 兜底识别：如果按钮文本不可访问，尝试判断“录制坐标命中的节点是否不可点击”
      if (isPointTargetDisabled(root)) {
        pointDisabledCount++;
        if (pointDisabledCount == 1) {
          Log.i(TAG, "point-target disabled candidate detected");
        }
        if (pointDisabledCount >= POINT_DISABLED_CONFIRM_COUNT) {
          pointDisabledCount = 0;
          lastSignalAt = now;
          noSignalTimeoutCount = 0;
          Log.i(TAG, "point-target disabled confirmed -> treat as sold-out");
          onSoldOutConfirmed(now);
          scheduleFastLoop(FAST_POLL_MS);
          return;
        }
      } else {
        pointDisabledCount = 0;
      }

      AccessibilityNodeInfo buyNode = findNodeByTexts(root, BUY_TEXTS);
      if (isNodeEnabled(buyNode)) {
        currentState = STATE_WATCH_BUY;
        lastSignalAt = now;
        noSignalTimeoutCount = 0;
        Log.d(TAG, "buy button enabled -> burst click");
        burstClick(buyNode, now);
      }

      // 强制刷新：长期没有识别信号时，连续两次超时后执行返回重进（不做自动滑动）
      if (now - lastSignalAt >= NO_SIGNAL_FORCE_REFRESH_MS) {
        noSignalTimeoutCount++;
        Log.w(TAG,
            "no signal for " + (now - lastSignalAt) + "ms timeoutCount=" + noSignalTimeoutCount);
        lastSignalAt = now;
        if (noSignalTimeoutCount == 1) {
          Log.i(TAG, "no_signal_timeout first aid -> swipe disabled");
        } else {
          noSignalTimeoutCount = 0;
          triggerBackAndReenter(now, "no_signal_timeout_x2");
        }
      }
      scheduleFastLoop(FAST_POLL_MS);
    }
  };

  private final Runnable reenterRunnable = new Runnable() {
    @Override
    public void run() {
      if (autoTouchPoint == null || !TouchEventManager.getInstance().isTouching()) {
        return;
      }
      currentState = STATE_REENTERING;
      boolean reentered = tryReEnterProduct();
      if (reentered) {
        Log.i(TAG, "reenter success");
        currentState = STATE_WATCH_BUY;
        reenterRetryCount = 0;
        reenterSwipeSearchCount = 0;
        reenterSwipePhase = SWIPE_PHASE_NONE;
        lastReenterActionWasSwipe = false;
        lastReenterSwipeDelayMs = 0L;
        soldOutBackoffUntil = System.currentTimeMillis() + 180L;
        scheduleFastLoop(FAST_POLL_MS);
        return;
      }
      reenterRetryCount++;
      Log.i(TAG, "reenter retry=" + reenterRetryCount + "/" + REENTER_MAX_RETRY);
      if (reenterRetryCount <= REENTER_MAX_RETRY) {
        long retryDelay =
            lastReenterActionWasSwipe ? lastReenterSwipeDelayMs : nextReenterRetryDelayMs();
        if (lastReenterActionWasSwipe) {
          Log.i(TAG, "reenter retry after swipe delay=" + retryDelay + "ms");
        }
        lastReenterActionWasSwipe = false;
        handler.postDelayed(reenterRunnable, retryDelay);
      } else {
        Log.w(TAG, "reenter failed: fallback to watch mode");
        currentState = STATE_WATCH_BUY;
        reenterRetryCount = 0;
        reenterSwipeSearchCount = 0;
        reenterSwipePhase = SWIPE_PHASE_NONE;
        lastReenterActionWasSwipe = false;
        lastReenterSwipeDelayMs = 0L;
        scheduleFastLoop(FAST_POLL_MS);
      }
    }
  };

  private void onSoldOutConfirmed(long now) {
    triggerBackAndReenter(now, "sold_out_confirmed");
  }

  private void scheduleFastLoop(long delayMs) {
    if (autoTouchPoint == null || !TouchEventManager.getInstance().isTouching()) {
      return;
    }
    handler.removeCallbacks(fastLoopRunnable);
    handler.postDelayed(fastLoopRunnable, delayMs);
  }

  private void burstClick(AccessibilityNodeInfo buyNode, long now) {
    if (now - lastBurstAt < FAST_POLL_MS) {
      return;
    }
    lastBurstAt = now;
    Rect rect = new Rect();
    buyNode.getBoundsInScreen(rect);
    int x = rect.centerX();
    int y = rect.centerY();
    if (x <= 0 || y <= 0) {
      x = autoTouchPoint.getX();
      y = autoTouchPoint.getY();
    }
    final int finalX = x;
    final int finalY = y;
    for (int i = 0; i < BURST_COUNT; i++) {
      long delay = i * BURST_GAP_MS;
      handler.postDelayed(new Runnable() {
        @Override
        public void run() {
          if (autoTouchPoint == null || !TouchEventManager.getInstance().isTouching()) {
            return;
          }
          tap(finalX, finalY, TAP_DURATION_MS);
        }
      }, delay);
    }
  }

  private boolean tryReEnterProduct() {
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root == null) {
      Log.w(TAG, "tryReEnterProduct: root null");
      return false;
    }
    String currentPkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
    if (!isTargetPackage(currentPkg)) {
      Log.w(TAG, "reenter out of target pkg=" + currentPkg + ", relaunch target");
      bringTargetAppToFront();
      return false;
    }

    String customKeyword = autoTouchPoint.getName();
    if (!isEmpty(customKeyword)) {
      if (clickByText(root, customKeyword)) {
        Log.i(TAG, "reenter by custom keyword=" + customKeyword);
        reenterSwipeSearchCount = 0;
        reenterSwipePhase = SWIPE_PHASE_NONE;
        lastReenterActionWasSwipe = false;
        lastReenterSwipeDelayMs = 0L;
        return true;
      }
      Log.w(TAG, "reenter custom keyword not found=" + customKeyword);
      if (reenterSwipePhase == SWIPE_PHASE_AFTER_DOWN) {
        reenterSwipeSearchCount++;
        reenterSwipePhase = SWIPE_PHASE_NONE;
        Log.i(TAG,
            "reenter search miss after swipe cycle " + reenterSwipeSearchCount + "/"
                + REENTER_MAX_SWIPE_SEARCH_COUNT);
      }
      if (reenterSwipeSearchCount < REENTER_MAX_SWIPE_SEARCH_COUNT) {
        if (reenterSwipePhase == SWIPE_PHASE_NONE) {
          boolean upSwipeResult = swipeUpForReenterRefresh();
          if (upSwipeResult) {
            reenterSwipePhase = SWIPE_PHASE_AFTER_UP;
            prepareSwipeRetryDelay();
            Log.i(TAG, "reenter swipe-up then wait " + lastReenterSwipeDelayMs + "ms");
            return false;
          }
        } else if (reenterSwipePhase == SWIPE_PHASE_AFTER_UP) {
          boolean downSwipeResult = swipeDownForReenterSearch();
          if (downSwipeResult) {
            reenterSwipePhase = SWIPE_PHASE_AFTER_DOWN;
            prepareSwipeRetryDelay();
            Log.i(TAG, "reenter swipe-down then wait " + lastReenterSwipeDelayMs + "ms");
            return false;
          }
        }
      }
      if (STRICT_REENTER_WITH_CUSTOM_KEYWORD) {
        boolean maybeDetailPage =
            containsText(root, BUY_TEXTS) || containsText(root, SOLD_OUT_TEXTS);
        if (maybeDetailPage) {
          boolean tapResult = tap(autoTouchPoint.getX(), autoTouchPoint.getY(), TAP_DURATION_MS);
          Log.i(TAG, "reenter by coordinate tap result=" + tapResult + " (strict_custom)");
          return tapResult;
        }
        Log.w(TAG, "strict_custom skip coordinate tap: not in detail-like page");
        return false;
      }
    }
    for (String text : REENTER_HINT_TEXTS) {
      if (clickByText(root, text)) {
        Log.i(TAG, "reenter by fallback keyword=" + text);
        return true;
      }
    }
    if (clickByText(root, "购")) {
      Log.i(TAG, "reenter by generic keyword=购");
      return true;
    }
    boolean tapResult = tap(autoTouchPoint.getX(), autoTouchPoint.getY(), TAP_DURATION_MS);
    Log.i(TAG, "reenter by coordinate tap result=" + tapResult);
    return tapResult;
  }

  private boolean isSoldOut(AccessibilityNodeInfo root, long now) {
    if (now - soldOutToastAt <= SOLD_OUT_TOAST_VALID_MS) {
      return true;
    }
    AccessibilityNodeInfo soldOutNode = findNodeByTexts(root, SOLD_OUT_TEXTS);
    if (soldOutNode != null && soldOutNode.isVisibleToUser()) {
      return true;
    }
    AccessibilityNodeInfo buyNode = findNodeByTexts(root, BUY_TEXTS);
    return buyNode != null && !isNodeEnabled(buyNode);
  }

  private boolean isPointTargetDisabled(AccessibilityNodeInfo root) {
    if (autoTouchPoint == null) {
      return false;
    }
    AccessibilityNodeInfo targetNode =
        findSmallestNodeAtPoint(root, autoTouchPoint.getX(), autoTouchPoint.getY());
    if (targetNode == null) {
      return false;
    }
    return !isNodeEnabled(targetNode);
  }

  private AccessibilityNodeInfo findSmallestNodeAtPoint(AccessibilityNodeInfo node, int x, int y) {
    if (node == null || !node.isVisibleToUser()) {
      return null;
    }
    Rect rect = new Rect();
    node.getBoundsInScreen(rect);
    if (!rect.contains(x, y)) {
      return null;
    }

    AccessibilityNodeInfo best = node;
    int bestArea = rect.width() * rect.height();
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      AccessibilityNodeInfo childBest = findSmallestNodeAtPoint(child, x, y);
      if (childBest == null) {
        continue;
      }
      Rect childRect = new Rect();
      childBest.getBoundsInScreen(childRect);
      int childArea = childRect.width() * childRect.height();
      if (childArea > 0 && childArea < bestArea) {
        best = childBest;
        bestArea = childArea;
      }
    }
    return best;
  }

  private void triggerBackAndReenter(long now, String reason) {
    soldOutBackoffUntil = now + SOLD_OUT_BACKOFF_MS;
    if (now - lastRefreshAt < REFRESH_COOLDOWN_MS) {
      currentState = STATE_SOLD_OUT_BACKOFF;
      Log.i(TAG, reason + " -> in cooldown, backoff only");
      return;
    }
    lastRefreshAt = now;
    currentState = STATE_SOLD_OUT_BACKOFF;

    if (SOLD_OUT_MODE == SOLD_OUT_MODE_STAY_PAGE) {
      Log.i(TAG, reason + " -> STAY_PAGE mode, keep polling current page");
      return;
    }

    boolean backResult = performGlobalAction(GLOBAL_ACTION_BACK);
    Log.i(TAG, reason + " -> perform BACK result=" + backResult);
    if (!backResult) {
      Log.w(TAG, reason + " -> BACK failed, keep polling current page");
      currentState = STATE_WATCH_BUY;
      return;
    }
    reenterRetryCount = 0;
    noSignalTimeoutCount = 0;
    reenterSwipeSearchCount = 0;
    reenterSwipePhase = SWIPE_PHASE_NONE;
    lastReenterActionWasSwipe = false;
    lastReenterSwipeDelayMs = 0L;
    handler.removeCallbacks(reenterRunnable);
    long backWaitMs = nextBackWaitMs();
    Log.i(TAG, reason + " -> schedule reenter after " + backWaitMs + "ms");
    handler.postDelayed(reenterRunnable, backWaitMs);
  }

  private boolean swipeUpForReenterRefresh() {
    int screenWidth = getResources().getDisplayMetrics().widthPixels;
    int screenHeight = getResources().getDisplayMetrics().heightPixels;
    if (screenWidth <= 0 || screenHeight <= 0) {
      return false;
    }
    int x =
        screenWidth / 2 + randomBetween(-(int) (screenWidth * 0.08f), (int) (screenWidth * 0.08f));
    int startY = (int) (screenHeight * randomBetweenFloat(0.52f, 0.72f));
    int distance = randomBetween(REENTER_SWIPE_UP_PX_MIN, REENTER_SWIPE_UP_PX_MAX);
    int endY = Math.max(startY - distance, (int) (screenHeight * 0.18f));
    if (endY >= startY) {
      return false;
    }
    long duration = randomBetweenLong(240L, 420L);
    Path path = new Path();
    path.moveTo(x, startY);
    path.lineTo(x, endY);
    GestureDescription gesture =
        new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
            .build();
    boolean result = dispatchGesture(gesture, null, null);
    Log.i(TAG,
        "reenter swipe-up start=(" + x + "," + startY + ") end=(" + x + "," + endY
            + ") distance=" + (startY - endY) + " dur=" + duration + "ms result=" + result);
    return result;
  }

  private boolean swipeDownForReenterSearch() {
    int screenWidth = getResources().getDisplayMetrics().widthPixels;
    int screenHeight = getResources().getDisplayMetrics().heightPixels;
    if (screenWidth <= 0 || screenHeight <= 0) {
      return false;
    }
    int x =
        screenWidth / 2 + randomBetween(-(int) (screenWidth * 0.08f), (int) (screenWidth * 0.08f));
    int startY = (int) (screenHeight * randomBetweenFloat(0.30f, 0.48f));
    int distance = randomBetween(REENTER_SWIPE_PX_MIN, REENTER_SWIPE_PX_MAX);
    int endY = Math.min(startY + distance, (int) (screenHeight * 0.90f));
    if (endY <= startY) {
      return false;
    }
    long duration = randomBetweenLong(260L, 420L);
    Path path = new Path();
    path.moveTo(x, startY);
    path.lineTo(x, endY);
    GestureDescription gesture =
        new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
            .build();
    boolean result = dispatchGesture(gesture, null, null);
    Log.i(TAG,
        "reenter swipe-down start=(" + x + "," + startY + ") end=(" + x + "," + endY
            + ") distance=" + (endY - startY) + " dur=" + duration + "ms result=" + result);
    return result;
  }

  private void prepareSwipeRetryDelay() {
    lastReenterSwipeDelayMs = randomBetweenLong(
        REENTER_SEARCH_AFTER_SWIPE_DELAY_MIN_MS, REENTER_SEARCH_AFTER_SWIPE_DELAY_MAX_MS);
    lastReenterActionWasSwipe = true;
  }

  private boolean containsText(AccessibilityNodeInfo root, String[] texts) {
    return findNodeByTexts(root, texts) != null;
  }

  private AccessibilityNodeInfo findNodeByTexts(AccessibilityNodeInfo root, String[] texts) {
    if (root == null || texts == null) {
      return null;
    }
    for (String target : texts) {
      List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(target);
      if (nodes == null || nodes.isEmpty()) {
        continue;
      }
      for (AccessibilityNodeInfo node : nodes) {
        if (node == null) {
          continue;
        }
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        boolean match = contains(text, target) || contains(desc, target);
        if (!match) {
          continue;
        }
        if (node.isVisibleToUser()) {
          return node;
        }
      }
    }
    return null;
  }

  private boolean clickByText(AccessibilityNodeInfo root, String text) {
    if (root == null || text == null || text.trim().isEmpty()) {
      return false;
    }
    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
    if (nodes == null || nodes.isEmpty()) {
      return false;
    }
    for (AccessibilityNodeInfo node : nodes) {
      if (node == null || !node.isVisibleToUser()) {
        continue;
      }
      if (clickNode(node)) {
        return true;
      }
      Rect rect = new Rect();
      node.getBoundsInScreen(rect);
      if (tap(rect.centerX(), rect.centerY(), TAP_DURATION_MS)) {
        return true;
      }
    }
    return false;
  }

  private boolean clickNode(AccessibilityNodeInfo node) {
    AccessibilityNodeInfo target = node;
    while (target != null) {
      if (target.isClickable() && target.isEnabled() && target.isVisibleToUser()) {
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
      }
      target = target.getParent();
    }
    return false;
  }

  private boolean tap(int x, int y, long durationMs) {
    if (x <= 0 || y <= 0) {
      return false;
    }
    Path path = new Path();
    path.moveTo(x, y);
    GestureDescription gesture =
        new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
            .build();
    return dispatchGesture(gesture, null, null);
  }

  private boolean containsAny(List<CharSequence> values, String[] targets) {
    if (values == null || values.isEmpty() || targets == null) {
      return false;
    }
    for (CharSequence value : values) {
      if (value == null) {
        continue;
      }
      String text = value.toString();
      for (String target : targets) {
        if (contains(text, target)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean contains(CharSequence value, String target) {
    return value != null && target != null && value.toString().contains(target);
  }

  private boolean isEmpty(String value) {
    return value == null || value.trim().isEmpty();
  }

  private boolean isTargetPackage(String currentPkg) {
    return !isEmpty(targetPackage) && targetPackage.equals(currentPkg);
  }

  private long nextBackWaitMs() {
    return randomBetweenLong(BACK_WAIT_MIN_MS, BACK_WAIT_MAX_MS);
  }

  private long nextReenterRetryDelayMs() {
    return randomBetweenLong(REENTER_RETRY_MIN_MS, REENTER_RETRY_MAX_MS);
  }

  private long randomBetweenLong(long min, long max) {
    if (max <= min) {
      return min;
    }
    return min + (long) (random.nextDouble() * (max - min + 1));
  }

  private int randomBetween(int min, int max) {
    if (max <= min) {
      return min;
    }
    return min + random.nextInt(max - min + 1);
  }

  private float randomBetweenFloat(float min, float max) {
    if (max <= min) {
      return min;
    }
    return min + random.nextFloat() * (max - min);
  }

  private void bringTargetAppToFront() {
    if (isEmpty(targetPackage)) {
      targetPackage = DEFAULT_TARGET_PACKAGE;
    }
    Intent launchIntent = getPackageManager().getLaunchIntentForPackage(targetPackage);
    if (launchIntent == null) {
      Log.w(TAG, "launch intent not found for target package=" + targetPackage);
      return;
    }
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      startActivity(launchIntent);
      Log.i(TAG, "relaunch target package=" + targetPackage);
    } catch (Exception e) {
      Log.w(TAG, "relaunch target failed: " + e.getMessage());
    }
  }

  private boolean isNodeEnabled(AccessibilityNodeInfo node) {
    if (node == null || !node.isVisibleToUser()) {
      return false;
    }
    AccessibilityNodeInfo target = node;
    while (target != null) {
      if (!target.isEnabled()) {
        return false;
      }
      target = target.getParent();
    }
    return true;
  }

  private void logHeartBeat(long now) {
    if (now - lastHeartBeatAt < HEART_BEAT_LOG_MS) {
      return;
    }
    lastHeartBeatAt = now;
    Log.i(TAG,
        "heartbeat state=" + stateName(currentState) + " loop=" + loopRound
            + " soldOutBackoff=" + Math.max(0, soldOutBackoffUntil - now) + "ms"
            + " retries=" + reenterRetryCount + " idle=" + Math.max(0, now - lastSignalAt) + "ms"
            + " pkg=" + lastWindowPackage);
  }

  private String stateName(int state) {
    switch (state) {
      case STATE_IDLE:
        return "IDLE";
      case STATE_WATCH_BUY:
        return "WATCH_BUY";
      case STATE_SOLD_OUT_BACKOFF:
        return "SOLD_OUT_BACKOFF";
      case STATE_REENTERING:
        return "REENTERING";
      default:
        return "UNKNOWN(" + state + ")";
    }
  }

  private String soldOutModeName() {
    return SOLD_OUT_MODE == SOLD_OUT_MODE_BACK_REENTER ? "BACK_REENTER" : "STAY_PAGE";
  }

  private String actionName(int action) {
    switch (action) {
      case TouchEvent.ACTION_START:
        return "START";
      case TouchEvent.ACTION_PAUSE:
        return "PAUSE";
      case TouchEvent.ACTION_CONTINUE:
        return "CONTINUE";
      case TouchEvent.ACTION_STOP:
        return "STOP";
      default:
        return "UNKNOWN(" + action + ")";
    }
  }
}
