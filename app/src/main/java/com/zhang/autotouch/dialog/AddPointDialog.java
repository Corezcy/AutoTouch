package com.zheng.autotouch.dialog;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.Group;
import com.zheng.autotouch.R;
import com.zheng.autotouch.bean.TouchPoint;
import com.zheng.autotouch.utils.SpUtils;
import com.zheng.autotouch.utils.ToastUtil;

public class AddPointDialog extends BaseServiceDialog implements View.OnClickListener {
  private static final String TAG = "AT-Config";
  private static final int DEFAULT_DELAY_SECONDS = 1;

  private EditText etName;
  private Group groupInput;
  private TextView tvHint;
  private int x;
  private int y;

  public AddPointDialog(@NonNull Context context) {
    super(context);
  }

  @Override
  protected int getLayoutId() {
    return R.layout.dialog_add_point;
  }

  @Override
  protected int getWidth() {
    return WindowManager.LayoutParams.MATCH_PARENT;
  }

  @Override
  protected int getHeight() {
    return WindowManager.LayoutParams.MATCH_PARENT;
  }

  @Override
  protected void onInited() {
    etName = findViewById(R.id.et_name);
    groupInput = findViewById(R.id.gl_input);
    tvHint = findViewById(R.id.tv_hint);
    findViewById(R.id.bt_commit).setOnClickListener(this);
    findViewById(R.id.bt_cancel).setOnClickListener(this);
  }

  @Override
  public boolean onTouchEvent(@NonNull MotionEvent event) {
    if (event.getAction() == MotionEvent.ACTION_UP) {
      x = (int) event.getRawX();
      y = (int) event.getRawY();
      tvHint.setVisibility(View.GONE);
      groupInput.setVisibility(View.VISIBLE);
    }
    return super.onTouchEvent(event);
  }

  @Override
  public void onClick(View v) {
    switch (v.getId()) {
      case R.id.bt_commit:
        if (x <= 0 || y <= 0) {
          ToastUtil.show("请先点击一次“立即购买”按钮位置");
          return;
        }
        String name = etName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
          ToastUtil.show("请输入商品关键词，例如：丙午马年");
          return;
        }
        TouchPoint touchPoint = new TouchPoint(name, x, y, DEFAULT_DELAY_SECONDS);
        SpUtils.setMainTouchPoint(getContext(), touchPoint);
        Log.i(TAG, "save point=(" + x + "," + y + ") keyword=" + name);
        ToastUtil.show("抢购点位已保存");
        dismiss();
        break;
      case R.id.bt_cancel:
        dismiss();
        break;
    }
  }
}
