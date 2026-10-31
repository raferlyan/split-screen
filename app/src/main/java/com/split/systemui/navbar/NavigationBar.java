package com.split.systemui.navbar;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerGlobal;
import android.os.Binder;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.InsetsFrameProvider;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.split.systemui.R;
import com.split.systemui.split.SplitCoordinator;

/**
 * 最简导航栏：返回、主页、分屏三个按钮。
 * 窗口类型用 TYPE_NAVIGATION_BAR，并通过 providedInsets 声明 navigationBars inset，
 * 这样 WM 会给应用留出底部空间，分屏的上下边界也会扣掉导航栏。
 */
public class NavigationBar {

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final SplitCoordinator mSplitCoordinator;
    private final Binder mInsetsOwner = new Binder();
    private LinearLayout mView;

    public NavigationBar(Context context, SplitCoordinator splitCoordinator) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
        mSplitCoordinator = splitCoordinator;
    }

    public void attach() {
        final int height = mContext.getResources().getDimensionPixelSize(R.dimen.nav_bar_height);

        mView = new LinearLayout(mContext);
        mView.setOrientation(LinearLayout.HORIZONTAL);
        mView.setGravity(Gravity.CENTER);
        mView.setBackgroundColor(Color.BLACK);
        mView.addView(button(R.drawable.ic_nav_back, "返回",
                v -> injectKey(KeyEvent.KEYCODE_BACK)));
        mView.addView(button(R.drawable.ic_nav_home, "主页",
                v -> injectKey(KeyEvent.KEYCODE_HOME)));
        mView.addView(button(R.drawable.ic_nav_split, "分屏",
                v -> mSplitCoordinator.onSplitButtonClicked()));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, height, TYPE_NAVIGATION_BAR,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH
                        | FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        lp.setTitle("NavigationBar");
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.providedInsets = new InsetsFrameProvider[] {
                new InsetsFrameProvider(mInsetsOwner, 0, WindowInsets.Type.navigationBars()),
                new InsetsFrameProvider(mInsetsOwner, 0, WindowInsets.Type.tappableElement()),
                new InsetsFrameProvider(mInsetsOwner, 0,
                        WindowInsets.Type.mandatorySystemGestures()),
        };
        mWindowManager.addView(mView, lp);
    }

    public void detach() {
        if (mView != null) {
            mWindowManager.removeViewImmediate(mView);
            mView = null;
        }
    }

    private ImageButton button(int icon, String desc, View.OnClickListener listener) {
        final int width = mContext.getResources().getDimensionPixelSize(R.dimen.nav_button_width);
        ImageButton b = new ImageButton(mContext);
        b.setImageResource(icon);
        b.setContentDescription(desc);
        b.setBackgroundResource(R.drawable.nav_button_bg);
        b.setOnClickListener(listener);
        b.setLayoutParams(new LinearLayout.LayoutParams(width,
                LinearLayout.LayoutParams.MATCH_PARENT));
        return b;
    }

    /** 和 AOSP KeyButtonView 一样，注入一对虚拟按键，交给 PhoneWindowManager 处理。 */
    private void injectKey(int keyCode) {
        final long now = SystemClock.uptimeMillis();
        sendKey(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        sendKey(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void sendKey(KeyEvent template) {
        KeyEvent ev = new KeyEvent(template.getDownTime(), template.getEventTime(),
                template.getAction(), template.getKeyCode(), 0 /* repeat */, 0 /* metaState */,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
        ev.setDisplayId(mContext.getDisplayId());
        InputManagerGlobal.getInstance().injectInputEvent(ev,
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }
}
