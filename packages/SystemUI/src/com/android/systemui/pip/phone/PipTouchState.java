/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.pip.phone;

import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;

/**
 * This keeps track of the touch state throughout the current touch gesture.
 */
public class PipTouchState {

    private ViewConfiguration mViewConfig;

    private VelocityTracker mVelocityTracker;
    private final PointF mDownTouch = new PointF();
    private final PointF mDownDelta = new PointF();
    private final PointF mLastTouch = new PointF();
    private final PointF mLastDelta = new PointF();
    private final PointF mVelocity = new PointF();
    private boolean mIsUserInteracting = false;
    private boolean mIsDragging = false;
    private boolean mStartedDragging = false;
    private boolean mAllowDraggingOffscreen = false;
    private int mActivePointerId;

    public PipTouchState(ViewConfiguration viewConfig) {
        mViewConfig = viewConfig;
    }

    /**
     * Processess a given touch event and updates the state.
     */
    public void onTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                // Initialize the velocity tracker
                initOrResetVelocityTracker();
                mActivePointerId = ev.getPointerId(0);
                mLastTouch.set(ev.getX(), ev.getY());
                mDownTouch.set(mLastTouch);
                mIsDragging = false;
                mStartedDragging = false;
                mAllowDraggingOffscreen = true;
                mIsUserInteracting = true;
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                // Update the velocity tracker
                mVelocityTracker.addMovement(ev);
                int pointerIndex = ev.findPointerIndex(mActivePointerId);
                float x = ev.getX(pointerIndex);
                float y = ev.getY(pointerIndex);
                mLastDelta.set(x - mLastTouch.x, y - mLastTouch.y);
                mDownDelta.set(x - mDownTouch.x, y - mDownTouch.y);

                boolean hasMovedBeyondTap = mDownDelta.length() > mViewConfig.getScaledTouchSlop();
                if (!mIsDragging) {
                    if (hasMovedBeyondTap) {
                        mIsDragging = true;
                        mStartedDragging = true;
                    }
                } else {
                    mStartedDragging = false;
                }
                mLastTouch.set(x, y);
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                // Update the velocity tracker
                mVelocityTracker.addMovement(ev);

                int pointerIndex = ev.getActionIndex();
                int pointerId = ev.getPointerId(pointerIndex);
                if (pointerId == mActivePointerId) {
                    // Select a new active pointer id and reset the movement state
                    final int newPointerIndex = (pointerIndex == 0) ? 1 : 0;
                    mActivePointerId = ev.getPointerId(newPointerIndex);
                    mLastTouch.set(ev.getX(newPointerIndex), ev.getY(newPointerIndex));
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                // Update the velocity tracker
                mVelocityTracker.addMovement(ev);
                mVelocityTracker.computeCurrentVelocity(1000,
                        mViewConfig.getScaledMaximumFlingVelocity());
                mVelocity.set(mVelocityTracker.getXVelocity(), mVelocityTracker.getYVelocity());

                int pointerIndex = ev.findPointerIndex(mActivePointerId);
                mLastTouch.set(ev.getX(pointerIndex), ev.getY(pointerIndex));

                // Fall through to clean up
            }
            case MotionEvent.ACTION_CANCEL: {
                mIsUserInteracting = false;
                recycleVelocityTracker();
                break;
            }
        }
    }

    /**
     * @return the velocity of the active touch pointer at the point it is lifted off the screen.
     */
    public PointF getVelocity() {
        return mVelocity;
    }

    /**
     * @return the last touch position of the active pointer.
     */
    public PointF getLastTouchPosition() {
        return mLastTouch;
    }

    /**
     * @return the movement delta between the last handled touch event and the previous touch
     *         position.
     */
    public PointF getLastTouchDelta() {
        return mLastDelta;
    }

    /**
     * @return the movement delta between the last handled touch event and the down touch
     *         position.
     */
    public PointF getDownTouchDelta() {
        return mDownDelta;
    }

    /**
     * @return whether the user has started dragging.
     */
    public boolean isDragging() {
        return mIsDragging;
    }

    /**
     * @return whether the user is currently interacting with the PiP.
     */
    public boolean isUserInteracting() {
        return mIsUserInteracting;
    }

    /**
     * @return whether the user has started dragging just in the last handled touch event.
     */
    public boolean startedDragging() {
        return mStartedDragging;
    }

    /**
     * Disallows dragging offscreen for the duration of the current gesture.
     */
    public void setDisallowDraggingOffscreen() {
        mAllowDraggingOffscreen = false;
    }

    /**
     * @return whether dragging offscreen is allowed during this gesture.
     */
    public boolean allowDraggingOffscreen() {
        return mAllowDraggingOffscreen;
    }

    private void initOrResetVelocityTracker() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        } else {
            mVelocityTracker.clear();
        }
    }

    private void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }
}
