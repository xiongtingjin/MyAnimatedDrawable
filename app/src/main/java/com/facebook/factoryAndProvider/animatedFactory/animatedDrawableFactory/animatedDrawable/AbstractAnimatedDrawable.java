package com.facebook.factoryAndProvider.animatedFactory.animatedDrawableFactory.animatedDrawable;

/**
 * Created by Administrator on 2017/3/28 0028.
 */

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.support.annotation.VisibleForTesting;
import android.view.View;
import android.widget.ImageView;

import com.facebook.common.DrawableWithCaches;
import com.facebook.common.time.MonotonicClock;
import com.facebook.factoryAndProvider.animatedFactory.animatedDrawableFactory.animatedBackend.AnimatedDrawableBackend;
import com.facebook.factoryAndProvider.animatedFactory.animatedDrawableFactory.animatedBackend.AnimatedDrawableCachingBackend;
import com.facebook.factoryAndProvider.animatedFactory.animatedDrawableFactory.other.AnimatedDrawableDiagnostics;
import com.facebook.factoryAndProvider.animatedFactory.animatedImageFactory.animatedImage.AnimatedImage;
import com.facebook.log.FLog;
import com.facebook.references.CloseableReference;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 一个渲染animated image的{@link Drawable}其中的格式的细节是对于。{@link AnimatedDrawableBackend}的抽象
 * 这个drawable不仅能作为一个{@link Animatable}当客户端开始或者结束动画，还能通过调用{@link Drawable#setLevel}
 * 作为一个层级的drawable当客户端驱动动画的时候
 * A {@link Drawable} that renders a animated image. The details of the format are abstracted by the
 * {@link AnimatedDrawableBackend} interface. The drawable can work either as an {@link Animatable}
 * where the client calls start/stop to animate it or it can work as a level-based drawable where
 * the client drives the animation by calling {@link Drawable#setLevel}.
 *
 * 这个类有两种方式让动画运行：
 * 1.在{@link AnimatedDrawable}中使用{@link ValueAnimator}(A)运行动画：
 *      原理：A.start()之后会不断的调用{@link #setLevel}-->{@link #onLevelChange}-->{@link #doInvalidateSelf}
 *      -->{@link #invalidateSelf}-->{@link Drawable.Callback#invalidateDrawable}（由于Drawable是附着在View上的，
 *      所以这里调用的就是{@link View#invalidateDrawable}）-->{@link View#invalidate}-->{@link ImageView#onDraw}
 *      -->{@link #draw},在最后的draw中绘制当前的帧，又由于{@link #setLevel}是不断的被调用直至动画的最后一帧，
 *      所以这样一来会完整地运行动画
 * 2.直接调用{@link #start}:
 *      原理：{@link #start}-->{@link Drawable#scheduleSelf(Runnable mStartTask,long)}-->{@link Drawable.Callback#scheduleSelf}
 *      （由于Drawable是附着在View上的，所以这里调用的就是{@link View#scheduleDrawable}）-->{@link #mStartTask}
 *      -->{@link #onStart}-->{@link #scheduleSelf(Runnable mNextFrameTask,long)}-->{@link #doInvalidateSelf}
 *      -->{@link #invalidateSelf}-->{@link Drawable.Callback#invalidateDrawable}（由于Drawable是附着在View上的，
 *      所以这里调用的就是{@link View#invalidateDrawable}）-->{@link View#invalidate}-->{@link ImageView#onDraw}
 *      -->{@link #draw},在最后的draw中绘制第一帧并再次调用{@link #scheduleSelf(Runnable mNextFrameTask,long)}，
 *      又因为在{@link #onStart}调用了{@link #scheduleSelf(Runnable mNextFrameTask,long)}所以此时第二帧也准备好了
 *      -->{@link #mNextFrameTask}-->{@link #onNextFrame()}-->{@link #computeAndScheduleNextFrame}-->{@link #doInvalidateSelf}
 *      就这样一直调用{@link #scheduleSelf(Runnable mNextFrameTask,long)}直至动画结束
 */
public abstract class AbstractAnimatedDrawable extends Drawable implements Animatable, DrawableWithCaches {

    private static final Class<?> TAG = AnimatedDrawable.class;

    private static final long WATCH_DOG_TIMER_POLL_INTERVAL_MS = 2000;
    private static final long WATCH_DOG_TIMER_MIN_TIMEOUT_MS = 1000;

    private static final int POLL_FOR_RENDERED_FRAME_MS = 5;
    private static final int NO_FRAME = -1;

    private final ScheduledExecutorService mScheduledExecutorServiceForUiThread;
    private final AnimatedDrawableDiagnostics mAnimatedDrawableDiagnostics;
    private final MonotonicClock mMonotonicClock;
    private final int mDurationMs;
    private final int mFrameCount;
    private final int mLoopCount;

    //用来在Canvas上面绘制的画笔
    // Paint used to draw on a Canvas
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect mDstRect = new Rect();
    private final Paint mTransparentPaint;

    private volatile String mLogId;

    private AnimatedDrawableCachingBackend mAnimatedDrawableBackend;
    private long mStartTimeMs;

    //被调度将要被绘制的帧。在0和mFrameCount - 1之间
    // Index of frame scheduled to be drawn. Between 0 and mFrameCount - 1
    private int mScheduledFrameNumber;

    //被调度将要被绘制的帧。但是不会被重新设置成0，一直在增长
    // Index of frame scheduled to be drawn but never is reset to zero. Keeps growing.
    private int mScheduledFrameMonotonicNumber;

    //下一个将要被绘制的帧，在0和mFrameCount - 1之间，可能落后与mScheduledFrameNumber，如果我们绘制过慢的话
    // Index of frame that will be drawn next. Between 0 and mFrameCount - 1. May fall behind
    // mScheduledFrameIndex if we can't keep up.
    private int mPendingRenderedFrameNumber;

    //与mPendingRenderedFrameNumber 一起使用，但是保持增长
    // Corresponds to mPendingRenderedFrameNumber but keeps growing.
    private int mPendingRenderedFrameMonotonicNumber;

    //最后一个被绘制的帧
    // Index of last frame that was drawn.
    private int mLastDrawnFrameNumber = -1;

    //与mLastDrawnFrameNumber配合使用
    // Corresponds to mLastDrawnFrameNumber but keeps growing.
    private int mLastDrawnFrameMonotonicNumber = -1;

    //最后一个被绘制的帧，与mLastDrawnFrameNumber一起使用
    // Bitmap for last drawn frame. Corresponds to mLastDrawnFrameNumber.
    private CloseableReference<Bitmap> mLastDrawnFrame;

    private boolean mWaitingForDraw;
    private long mLastInvalidateTimeMs = -1;

    private boolean mIsRunning;
    private boolean mHaveWatchdogScheduled;

    private float mSx = 1f;
    private float mSy = 1f;
    private boolean mApplyTransformation;
    private boolean mInvalidateTaskScheduled;
    private long mNextFrameTaskMs = -1;

    private boolean mIsPaused = false;

    private final Runnable mStartTask = new Runnable() {
        @Override
        public void run() {
            onStart();
        }
    };

    private final Runnable mNextFrameTask = new Runnable() {
        @Override
        public void run() {
            FLog.v(TAG, "(%s) Next Frame Task", mLogId);
            onNextFrame();
        }
    };

    private final Runnable mInvalidateTask = new Runnable() {
        @Override
        public void run() {
            FLog.v(TAG, "(%s) Invalidate Task", mLogId);
            mInvalidateTaskScheduled = false;
            doInvalidateSelf();
        }
    };

    private final Runnable mWatchdogTask = new Runnable() {
        @Override
        public void run() {
            FLog.v(TAG, "(%s) Watchdog Task", mLogId);
            doWatchdogCheck();
        }
    };

    public AbstractAnimatedDrawable(
            ScheduledExecutorService scheduledExecutorServiceForUiThread,
            AnimatedDrawableCachingBackend animatedDrawableBackend,
            AnimatedDrawableDiagnostics animatedDrawableDiagnostics,
            MonotonicClock monotonicClock) {
        mScheduledExecutorServiceForUiThread = scheduledExecutorServiceForUiThread;
        mAnimatedDrawableBackend = animatedDrawableBackend;
        mAnimatedDrawableDiagnostics = animatedDrawableDiagnostics;
        mMonotonicClock = monotonicClock;
        mDurationMs = mAnimatedDrawableBackend.getDurationMs();
        mFrameCount = mAnimatedDrawableBackend.getFrameCount();
        mAnimatedDrawableDiagnostics.setBackend(mAnimatedDrawableBackend);
        mLoopCount = mAnimatedDrawableBackend.getLoopCount();
        mTransparentPaint = new Paint();
        mTransparentPaint.setColor(Color.TRANSPARENT);
        mTransparentPaint.setStyle(Paint.Style.FILL);

        //当没有产生动画的时候，显示第一张预览图
        // Show last frame when not animating.
        resetToPreviewFrame();
    }

    private void resetToPreviewFrame() {
        mScheduledFrameNumber = mAnimatedDrawableBackend.getFrameForPreview();
        mScheduledFrameMonotonicNumber = mScheduledFrameNumber;
        mPendingRenderedFrameNumber = NO_FRAME;
        mPendingRenderedFrameMonotonicNumber = NO_FRAME;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        if (mLastDrawnFrame != null) {
            mLastDrawnFrame.close();
            mLastDrawnFrame = null;
        }
    }


    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        mApplyTransformation = true;
        if (mLastDrawnFrame != null) {
            mLastDrawnFrame.close();
            mLastDrawnFrame = null;
        }
        mLastDrawnFrameNumber = -1;
        mLastDrawnFrameMonotonicNumber = -1;
        mAnimatedDrawableBackend.dropCaches();
    }

    private void onStart() {
        if (!mIsRunning) {
            return;
        }
        mAnimatedDrawableDiagnostics.onStartMethodBegin();
        try {
            mStartTimeMs = mMonotonicClock.now();

            if (mIsPaused) {
                mStartTimeMs -= mAnimatedDrawableBackend.getTimestampMsForFrame(mScheduledFrameNumber);
            } else {
                mScheduledFrameNumber = 0;
                mScheduledFrameMonotonicNumber = 0;
            }

            long nextFrameMs = mStartTimeMs + mAnimatedDrawableBackend.getDurationMsForFrame(0);
            scheduleSelf(mNextFrameTask, nextFrameMs);
            mNextFrameTaskMs = nextFrameMs;
            doInvalidateSelf();
        } finally {
            mAnimatedDrawableDiagnostics.onStartMethodEnd();
        }
    }

    private void onNextFrame() {
        mNextFrameTaskMs = -1;
        if (!mIsRunning) {
            return;
        }
        if (mDurationMs == 0) {
            return;
        }
        mAnimatedDrawableDiagnostics.onNextFrameMethodBegin();
        try {
            computeAndScheduleNextFrame(true /* schedule next frame */);
        } finally {
            mAnimatedDrawableDiagnostics.onNextFrameMethodEnd();
        }
    }

    private void computeAndScheduleNextFrame(boolean scheduleNextFrame) {
        if (mDurationMs == 0) {
            return;
        }
        long nowMs = mMonotonicClock.now();
        int loops = (int) ((nowMs - mStartTimeMs) / mDurationMs);
        if (mLoopCount != AnimatedImage.LOOP_COUNT_INFINITE && loops >= mLoopCount) {
            //we stop the animation if we have exceeded the total loop count
            return;
        }
        int timestampMs = (int) ((nowMs - mStartTimeMs) % mDurationMs);
        int newCurrentFrameNumber = mAnimatedDrawableBackend.getFrameForTimestampMs(timestampMs);
        boolean changed = mScheduledFrameNumber != newCurrentFrameNumber;
        mScheduledFrameNumber = newCurrentFrameNumber;
        mScheduledFrameMonotonicNumber = loops * mFrameCount + newCurrentFrameNumber;

        if (!scheduleNextFrame) {
            // We're about to draw. We don't need to schedule anything because we're going to draw
            // that frame right now. the onDraw method just wants to make sure the current frame is set.
            return;
        }

        if (changed) {
            doInvalidateSelf();
        } else {
            int durationMs = mAnimatedDrawableBackend.getTimestampMsForFrame(mScheduledFrameNumber) +
                    mAnimatedDrawableBackend.getDurationMsForFrame(mScheduledFrameNumber) -
                    timestampMs;
            int nextFrame = (mScheduledFrameNumber + 1) % mFrameCount;
            long nextFrameMs = nowMs + durationMs;
            if (mNextFrameTaskMs == -1 || mNextFrameTaskMs > nextFrameMs) {
                FLog.v(TAG, "(%s) Next frame (%d) in %d ms", mLogId, nextFrame, durationMs);
                unscheduleSelf(mNextFrameTask); // Cancel any existing task.
                scheduleSelf(mNextFrameTask, nextFrameMs);
                mNextFrameTaskMs = nextFrameMs;
            }
        }
    }

    @Override
    public void draw(Canvas canvas) {
        mAnimatedDrawableDiagnostics.onDrawMethodBegin();
        try {
            mWaitingForDraw = false;
            if (mIsRunning && !mHaveWatchdogScheduled) {
                mScheduledExecutorServiceForUiThread.schedule(
                        mWatchdogTask,
                        WATCH_DOG_TIMER_POLL_INTERVAL_MS,
                        TimeUnit.MILLISECONDS);
                mHaveWatchdogScheduled = true;
            }

            if (mApplyTransformation) {
                mDstRect.set(getBounds());
                if (!mDstRect.isEmpty()) {
                    AnimatedDrawableCachingBackend newBackend =
                            mAnimatedDrawableBackend.forNewBounds(mDstRect);
                    if (newBackend != mAnimatedDrawableBackend) {
                        mAnimatedDrawableBackend.dropCaches();
                        mAnimatedDrawableBackend = newBackend;
                        mAnimatedDrawableDiagnostics.setBackend(newBackend);
                    }
                    mSx = (float) mDstRect.width() / mAnimatedDrawableBackend.getRenderedWidth();
                    mSy = (float) mDstRect.height() / mAnimatedDrawableBackend.getRenderedHeight();
                    mApplyTransformation = false;
                }
            }

            if (mDstRect.isEmpty()) {
                // Don't try to draw if the dest rect is empty.
                return;
            }

            canvas.save();
            canvas.scale(mSx, mSy);

            // TODO(6169940) we overdraw if both pending frame is ready and current frame is ready.
            boolean didDrawFrame = false;
            if (mPendingRenderedFrameNumber != NO_FRAME) {
                // We tried to render a frame and it wasn't yet ready. See if it's ready now.
                boolean rendered =
                        renderFrame(canvas, mPendingRenderedFrameNumber, mPendingRenderedFrameMonotonicNumber);
                didDrawFrame |= rendered;
                if (rendered) {
                    FLog.v(TAG, "(%s) Rendered pending frame %d", mLogId, mPendingRenderedFrameNumber);
                    mPendingRenderedFrameNumber = NO_FRAME;
                    mPendingRenderedFrameMonotonicNumber = NO_FRAME;
                } else {
                    // Try again later.
                    FLog.v(TAG, "(%s) Trying again later for pending %d", mLogId, mPendingRenderedFrameNumber);
                    scheduleInvalidatePoll();
                }
            }

            if (mPendingRenderedFrameNumber == NO_FRAME) {
                // We don't have a frame that's pending so render the current frame.
                if (mIsRunning) {
                    computeAndScheduleNextFrame(false /* don't schedule yet */);
                }
                boolean rendered = renderFrame(
                        canvas,
                        mScheduledFrameNumber,
                        mScheduledFrameMonotonicNumber);
                didDrawFrame |= rendered;
                if (rendered) {
                    FLog.v(TAG, "(%s) Rendered current frame %d", mLogId, mScheduledFrameNumber);
                    if (mIsRunning) {
                        computeAndScheduleNextFrame(true /* schedule next frame */);
                    }
                } else {
                    FLog.v(TAG, "(%s) Trying again later for current %d", mLogId, mScheduledFrameNumber);
                    mPendingRenderedFrameNumber = mScheduledFrameNumber;
                    mPendingRenderedFrameMonotonicNumber = mScheduledFrameMonotonicNumber;
                    scheduleInvalidatePoll();
                }
            }

            if (!didDrawFrame) {
                if (mLastDrawnFrame != null) {
                    canvas.drawBitmap(mLastDrawnFrame.get(), 0f, 0f, mPaint);
                    didDrawFrame = true;
                    FLog.v(TAG, "(%s) Rendered last known frame %d", mLogId, mLastDrawnFrameNumber);
                }
            }

            if (!didDrawFrame) {
                // Last ditch effort, use preview bitmap.
                CloseableReference<Bitmap> previewBitmapReference =
                        mAnimatedDrawableBackend.getPreviewBitmap();
                if (previewBitmapReference != null) {
                    canvas.drawBitmap(previewBitmapReference.get(), 0f, 0f, mPaint);
                    previewBitmapReference.close();
                    FLog.v(TAG, "(%s) Rendered preview frame", mLogId);
                    didDrawFrame = true;
                }
            }

            if (!didDrawFrame) {
                // TODO(6169940) this may not be necessary. Confirm with Rich.
                canvas.drawRect(0, 0, mDstRect.width(), mDstRect.height(), mTransparentPaint);
                FLog.v(TAG, "(%s) Failed to draw a frame", mLogId);
            }

            canvas.restore();
            mAnimatedDrawableDiagnostics.drawDebugOverlay(canvas, mDstRect);
        } finally {
            mAnimatedDrawableDiagnostics.onDrawMethodEnd();
        }
    }

    /**
     * 调用一个task来使drawable无效，
     * Schedule a task to invalidate the drawable. Used to poll for a rendered frame.
     */
    private void scheduleInvalidatePoll() {
        if (mInvalidateTaskScheduled) {
            return;
        }
        mInvalidateTaskScheduled = true;
        scheduleSelf(mInvalidateTask, POLL_FOR_RENDERED_FRAME_MS);
    }


    /**
     * 绘制指定的帧到canvas上
     * Renders the specified frame to the canvas.
     *
     * @param canvas the canvas to render to
     * @param frameNumber the relative frame number (between 0 and frame count)
     * @param frameMonotonicNumber the absolute frame number for stats purposes
     * @return whether the frame was available and was rendered
     */
    private boolean renderFrame(
            Canvas canvas,
            int frameNumber,
            int frameMonotonicNumber) {
        CloseableReference<Bitmap> bitmapReference =
                mAnimatedDrawableBackend.getBitmapForFrame(frameNumber);
        if (bitmapReference != null) {
            canvas.drawBitmap(bitmapReference.get(), 0f, 0f, mPaint);
            if (mLastDrawnFrame != null) {
                mLastDrawnFrame.close();
            }

            if (mIsRunning && frameMonotonicNumber > mLastDrawnFrameMonotonicNumber) {
                int droppedFrames = frameMonotonicNumber - mLastDrawnFrameMonotonicNumber - 1;
                mAnimatedDrawableDiagnostics.incrementDrawnFrames(1);
                mAnimatedDrawableDiagnostics.incrementDroppedFrames(droppedFrames);
                if (droppedFrames > 0) {
                    FLog.v(TAG, "(%s) Dropped %d frames", mLogId, droppedFrames);
                }
            }
            mLastDrawnFrame = bitmapReference;
            mLastDrawnFrameNumber = frameNumber;
            mLastDrawnFrameMonotonicNumber = frameMonotonicNumber;
            FLog.v(TAG, "(%s) Drew frame %d", mLogId, frameNumber);
            return true;
        }
        return false;
    }

    /**
     * 检查确保减少我们的缓存，如果我们没有对动画进行绘制。
     * 没有一个可靠的方式来检测一个drawable是否是在view中活跃的一部分，所以我们使用了heuristic来代替
     * Checks to make sure we drop our caches if we haven't drawn in a while. There's no reliable
     * way for a Drawable to determine if it's still actively part of a View, so we use a heuristic
     * instead.
     */
    private void doWatchdogCheck() {
        mHaveWatchdogScheduled = false;
        if (!mIsRunning) {
            return;
        }
        long now = mMonotonicClock.now();

        //超时：如果从绘制失效以来已经过了2秒了
        // Timeout if it's been more than 2 seconds with drawn since invalidation.
        boolean hasNotDrawnWithinTimeout =
                mWaitingForDraw && now - mLastInvalidateTimeMs > WATCH_DOG_TIMER_MIN_TIMEOUT_MS;

        //同时onNextFrame也超时了2秒
        // Also timeout onNextFrame is more than 2 seconds late.
        boolean hasNotAdvancedFrameWithinTimeout =
                mNextFrameTaskMs != -1 && now - mNextFrameTaskMs > WATCH_DOG_TIMER_MIN_TIMEOUT_MS;

        if (hasNotDrawnWithinTimeout || hasNotAdvancedFrameWithinTimeout) {
            dropCaches();
            doInvalidateSelf();
        } else {
            mScheduledExecutorServiceForUiThread.schedule(
                    mWatchdogTask,
                    WATCH_DOG_TIMER_POLL_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
            mHaveWatchdogScheduled = true;
        }
    }

    private void doInvalidateSelf() {
        mWaitingForDraw = true;
        mLastInvalidateTimeMs = mMonotonicClock.now();
        invalidateSelf();
    }


    @Override
    public void start() {
        if (mDurationMs == 0 || mFrameCount <= 1) {
            return;
        }
        mIsRunning = true;
        scheduleSelf(mStartTask, mMonotonicClock.now());
    }

    @Override
    public void stop() {
        mIsPaused = false;
        mIsRunning = false;
    }

    public void pause() {
        mIsPaused = true;
        mIsRunning = false;
    }

    @Override
    public boolean isRunning() {
        return mIsRunning;
    }

    @Override
    protected boolean onLevelChange(int level) {
        if (mIsRunning) {
            //如果客户端调用了start，他们希望我们运行动画。在这种情况下，我们忽略level的变化
            // If the client called start on us, they expect us to run the animation. In that case,
            // we ignore level changes.
            return false;
        }
        int frame = mAnimatedDrawableBackend.getFrameForTimestampMs(level);
        if (frame == mScheduledFrameNumber) {
            return false;
        }

        try {
            mScheduledFrameNumber = frame;
            mScheduledFrameMonotonicNumber = frame;
            doInvalidateSelf();
            return true;
        } catch (IllegalStateException e) {
            //image已经被销毁了
            // The underlying image was disposed.
            return false;
        }
    }

    @Override
    public void dropCaches() {
        FLog.v(TAG, "(%s) Dropping caches", mLogId);
        if (mLastDrawnFrame != null) {
            mLastDrawnFrame.close();
            mLastDrawnFrame = null;
            mLastDrawnFrameNumber = -1;
            mLastDrawnFrameMonotonicNumber = -1;
        }
        mAnimatedDrawableBackend.dropCaches();
    }

    //--------------------------下面都是get set方法--------------------------

    /**
     *
     * Returns whether a previous call to {@link #draw} would have rendered a frame.
     *
     * @return whether a previous call to {@link #draw} would have rendered a frame
     */
    public boolean didLastDrawRender() {
        return mLastDrawnFrame != null;
    }

    @VisibleForTesting
    boolean isWaitingForDraw() {
        return mWaitingForDraw;
    }

    @VisibleForTesting
    boolean isWaitingForNextFrame() {
        return mNextFrameTaskMs != -1;
    }

    @VisibleForTesting
    int getScheduledFrameNumber() {
        return mScheduledFrameNumber;
    }

    /**
     * Get the animation duration of 1 loop.
     * @return the animation duration in ms
     */
    public int getDuration() {
        return mDurationMs;
    }

    /**
     * Get the number of frames for the animation.
     * @return the number of frames of the animation
     */
    public int getFrameCount() {
        return mFrameCount;
    }

    /**
     * Get the loop count of the animation.
     * The returned value is either {@link AnimatedImage#LOOP_COUNT_INFINITE} if the animation
     * is repeated infinitely or a positive integer that corresponds to the number of loops.
     * @return the loop count of the animation or {@link AnimatedImage#LOOP_COUNT_INFINITE}
     */
    public int getLoopCount() {
        return mLoopCount;
    }

    public AnimatedDrawableCachingBackend getAnimatedDrawableBackend() {
        return mAnimatedDrawableBackend;
    }

    /**
     * 设置logged时候的id，在debug的时候使用
     * Sets an id that will be logged with any of the logging calls. Useful for debugging.
     *
     * @param logId the id to log
     */
    public void setLogId(String logId) {
        mLogId = logId;
    }

    @Override
    public int getIntrinsicWidth() {
        return mAnimatedDrawableBackend.getWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mAnimatedDrawableBackend.getHeight();
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        doInvalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mPaint.setColorFilter(cf);
        doInvalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
