package com.zheng.autotouch.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.zheng.autotouch.R;
import com.zheng.autotouch.TouchEventManager;
import com.zheng.autotouch.bean.TouchEvent;
import com.zheng.autotouch.bean.TouchPoint;
import com.zheng.autotouch.utils.DensityUtil;
import com.zheng.autotouch.utils.DialogUtils;
import com.zheng.autotouch.utils.SpUtils;
import com.zheng.autotouch.utils.ToastUtil;

/**
 * 精简菜单：只保留设置点位、开始/停止、退出
 */
public class MenuDialog extends BaseServiceDialog implements View.OnClickListener {
  private static final String TAG = "AT-Config";
  private Button btAction;
  private TextView tvStatus;
  private AddPointDialog addPointDialog;
  private Listener listener;
  private TouchPoint mainTouchPoint;

  public MenuDialog(@NonNull Context context) {
    super(context);
  }

  @Override
  protected int getLayoutId() {
    return R.layout.dialog_menu;
  }

  @Override
  protected int getWidth() {
    return DensityUtil.dip2px(getContext(), 320);
  }

  @Override
  protected int getHeight() {
    return WindowManager.LayoutParams.WRAP_CONTENT;
  }

  @Override
  protected void onInited() {
    setCanceledOnTouchOutside(true);
    findViewById(R.id.bt_exit).setOnClickListener(this);
    findViewById(R.id.bt_add).setOnClickListener(this);
    btAction = findViewById(R.id.bt_action);
    btAction.setOnClickListener(this);
    tvStatus = findViewById(R.id.tv_status);
    setOnDismissListener(new OnDismissListener() {
      @Override
      public void onDismiss(DialogInterface dialog) {
        if (TouchEventManager.getInstance().isPaused()) {
          TouchEvent.postContinueAction();
        }
      }
    });
  }

  @Override
  protected void onStart() {
    super.onStart();
    // 打开菜单时暂停，关闭菜单时自动继续
    TouchEvent.postPauseAction();
    refreshUi();
  }

  @Override
  public void onClick(View v) {
    switch (v.getId()) {
      case R.id.bt_add:
        DialogUtils.dismiss(addPointDialog);
        addPointDialog = new AddPointDialog(getContext());
        addPointDialog.setOnDismissListener(new OnDismissListener() {
          @Override
          public void onDismiss(DialogInterface dialog) {
            MenuDialog.this.show();
          }
        });
        addPointDialog.show();
        dismiss();
        break;
      case R.id.bt_action:
        onClickAction();
        break;
      case R.id.bt_exit:
        TouchEvent.postStopAction();
        if (listener != null) {
          listener.onExitService();
        }
        break;
      default:
        break;
    }
  }

  private void onClickAction() {
    if (TouchEventManager.getInstance().isTouching()) {
      TouchEvent.postStopAction();
      ToastUtil.show("已停止抢购");
      refreshUi();
      return;
    }
    mainTouchPoint = SpUtils.getMainTouchPoint(getContext());
    if (mainTouchPoint == null) {
      ToastUtil.show("请先设置抢购点位");
      return;
    }
    Log.i(TAG, "start with point=(" + mainTouchPoint.getX() + "," + mainTouchPoint.getY()
        + ") keyword=" + mainTouchPoint.getName());
    dismiss();
    TouchEvent.postStartAction(mainTouchPoint);
    ToastUtil.show("已开始极速抢购");
  }

  private void refreshUi() {
    mainTouchPoint = SpUtils.getMainTouchPoint(getContext());
    boolean touching = TouchEventManager.getInstance().isTouching();
    btAction.setText(touching ? "停止抢购" : "开始抢购");
    if (mainTouchPoint == null) {
      tvStatus.setText("状态：未设置点位（先点“设置点位”）");
      return;
    }
    tvStatus.setText("状态：已设置，关键词：" + mainTouchPoint.getName());
  }

  public void setListener(Listener listener) {
    this.listener = listener;
  }

  public interface Listener {
    /**
     * 关闭助手
     */
    void onExitService();
  }
}
