package com.split.systemui.split;

import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.split.systemui.R;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 右侧滑出的应用选择列表。
 * 应用列表在后台线程加载并缓存，第二次打开直接显示缓存、同时后台刷新，避免点按钮后等加载。
 */
class AppPickerPanel {
    private static final long ANIM_MS = 220;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mBgHandler;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Consumer<ComponentName> mOnPick;
    private final AppAdapter mAdapter = new AppAdapter();

    // 界面只 inflate 一次，之后每次显示只是 addView / removeView，不再重新创建 View 和 RecyclerView
    private final View mRoot;
    private final View mPanel;
    private final View mScrim;
    private final int mPanelWidth;
    private boolean mShowing;
    private boolean mAttached;
    private String mExcludePackage;
    private List<AppEntry> mAllApps = new ArrayList<>();

    AppPickerPanel(Context context, Handler bgHandler, Consumer<ComponentName> onPick) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
        mBgHandler = bgHandler;
        mOnPick = onPick;
        mPanelWidth = context.getResources().getDimensionPixelSize(R.dimen.picker_width);

        mRoot = LayoutInflater.from(context).inflate(R.layout.app_picker, null);
        mPanel = mRoot.findViewById(R.id.panel);
        mScrim = mRoot.findViewById(R.id.scrim);
        mScrim.setOnClickListener(v -> dismiss(true));
        RecyclerView list = mRoot.findViewById(R.id.app_list);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(mAdapter);
        list.setItemAnimator(null);
        // 开机就在后台把应用列表和图标加载好，点按钮时不用等
        loadAppsAsync();
    }

    boolean isShowing() {
        return mShowing;
    }

    void show(String excludePackage) {
        if (mShowing) return;
        mShowing = true;
        mExcludePackage = excludePackage;
        mAdapter.submit(filter(mAllApps));
        loadAppsAsync();

        mPanel.animate().cancel();
        mScrim.animate().cancel();
        if (!mAttached) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    TYPE_NAVIGATION_BAR_PANEL, FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("SplitAppPicker");
            lp.setFitInsetsTypes(0);
            lp.windowAnimations = 0;
            mWindowManager.addView(mRoot, lp);
            mAttached = true;
            mPanel.setTranslationX(mPanelWidth);
            mScrim.setAlpha(0f);
        }

        // 面板从右侧滑入，遮罩淡入；只动 translation / alpha，不触发重新布局
        mPanel.animate().translationX(0).setDuration(ANIM_MS)
                .setInterpolator(new DecelerateInterpolator()).start();
        mScrim.animate().alpha(1f).setDuration(ANIM_MS).start();
    }

    void dismiss(boolean animate) {
        if (!mShowing) return;
        mShowing = false;
        if (!animate) {
            detach();
            return;
        }
        mScrim.animate().alpha(0f).setDuration(ANIM_MS).start();
        mPanel.animate().translationX(mPanelWidth).setDuration(ANIM_MS)
                .withEndAction(() -> {
                    if (!mShowing) detach();
                }).start();
    }

    private void detach() {
        if (mAttached) {
            mWindowManager.removeViewImmediate(mRoot);
            mAttached = false;
        }
    }

    private void onPick(AppEntry entry) {
        // 先发起分屏，再收起面板：分屏过渡和面板退出动画同时进行
        mOnPick.accept(entry.component);
        dismiss(true);
    }

    private void loadAppsAsync() {
        mBgHandler.post(() -> {
            LauncherApps launcherApps = mContext.getSystemService(LauncherApps.class);
            List<AppEntry> apps = new ArrayList<>();
            for (LauncherActivityInfo info :
                    launcherApps.getActivityList(null, Process.myUserHandle())) {
                if (info.getComponentName().getPackageName().equals(mContext.getPackageName())) {
                    continue;
                }
                apps.add(new AppEntry(info.getComponentName(), info.getLabel().toString(),
                        info.getBadgedIcon(0)));
            }
            apps.sort(Comparator.comparing(a -> a.label));
            mMainHandler.post(() -> {
                mAllApps = apps;
                if (mShowing) mAdapter.submit(filter(apps));
            });
        });
    }

    private List<AppEntry> filter(List<AppEntry> apps) {
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry a : apps) {
            if (!a.component.getPackageName().equals(mExcludePackage)) out.add(a);
        }
        return out;
    }

    private static final class AppEntry {
        final ComponentName component;
        final String label;
        final Drawable icon;

        AppEntry(ComponentName component, String label, Drawable icon) {
            this.component = component;
            this.label = label;
            this.icon = icon;
        }
    }

    private final class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {
        private List<AppEntry> mItems = new ArrayList<>();

        void submit(List<AppEntry> items) {
            mItems = items;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.app_picker_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            AppEntry entry = mItems.get(position);
            holder.icon.setImageDrawable(entry.icon);
            holder.label.setText(entry.label);
            holder.itemView.setOnClickListener(v -> onPick(entry));
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;

            Holder(View v) {
                super(v);
                icon = v.findViewById(R.id.icon);
                label = v.findViewById(R.id.label);
            }
        }
    }
}
