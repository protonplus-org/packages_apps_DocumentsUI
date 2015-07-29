/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkState;

import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.Adapter;
import android.support.v7.widget.RecyclerView.AdapterDataObserver;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.GestureDetector;
import android.view.GestureDetector.OnGestureListener;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.android.internal.util.Preconditions;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * MultiSelectManager adds traditional multi-item selection support to RecyclerView.
 */
public final class MultiSelectManager {

    private static final String TAG = "MultiSelectManager";
    private static final boolean DEBUG = false;

    private final Selection mSelection = new Selection();

    // Only created when selection is cleared.
    private Selection mIntermediateSelection;

    private Ranger mRanger;
    private final List<MultiSelectManager.Callback> mCallbacks = new ArrayList<>(1);

    private Adapter<?> mAdapter;
    private RecyclerViewHelper mHelper;

    /**
     * @param recyclerView
     * @param gestureDelegate Option delage gesture listener.
     */
    public MultiSelectManager(final RecyclerView recyclerView, OnGestureListener gestureDelegate) {
        this(
                recyclerView.getAdapter(),
                new RecyclerViewHelper() {
                    @Override
                    public int findEventPosition(MotionEvent e) {
                        View view = recyclerView.findChildViewUnder(e.getX(), e.getY());
                        return view != null
                                ? recyclerView.getChildAdapterPosition(view)
                                : RecyclerView.NO_POSITION;
                    }
                });

        GestureDetector.SimpleOnGestureListener listener =
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        return MultiSelectManager.this.onSingleTapUp(e);
                    }
                    @Override
                    public void onLongPress(MotionEvent e) {
                        MultiSelectManager.this.onLongPress(e);
                    }
                };

        final GestureDetector detector = new GestureDetector(
                recyclerView.getContext(),
                gestureDelegate == null
                        ? listener
                        : new CompositeOnGestureListener(listener, gestureDelegate));

        recyclerView.addOnItemTouchListener(
                new RecyclerView.OnItemTouchListener() {
                    public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                        detector.onTouchEvent(e);
                        return false;
                    }
                    public void onTouchEvent(RecyclerView rv, MotionEvent e) {}
                    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
                });
    }

    /**
     * Constructs a new instance with {@code adapter} and {@code helper}.
     * @param adapter
     * @param helper
     * @hide
     */
    @VisibleForTesting
    MultiSelectManager(Adapter<?> adapter, RecyclerViewHelper helper) {
        checkNotNull(adapter, "'adapter' cannot be null.");
        checkNotNull(helper, "'helper' cannot be null.");

        mHelper = helper;
        mAdapter = adapter;

        mAdapter.registerAdapterDataObserver(
                new AdapterDataObserver() {

                    @Override
                    public void onChanged() {
                        mSelection.clear();
                    }

                    @Override
                    public void onItemRangeChanged(
                            int positionStart, int itemCount, Object payload) {
                        // No change in position. Ignoring.
                    }

                    @Override
                    public void onItemRangeInserted(int positionStart, int itemCount) {
                        mSelection.expand(positionStart, itemCount);
                    }

                    @Override
                    public void onItemRangeRemoved(int positionStart, int itemCount) {
                        mSelection.collapse(positionStart, itemCount);
                    }

                    @Override
                    public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
                        throw new UnsupportedOperationException();
                    }
                });
    }

    /**
     * Adds {@code callback} such that it will be notified when {@code MultiSelectManager}
     * events occur.
     *
     * @param callback
     */
    public void addCallback(MultiSelectManager.Callback callback) {
        mCallbacks.add(callback);
    }

    /**
     * Returns a Selection object that provides a live view
     * on the current selection. Callers wishing to get
     *
     * @see #getSelectionSnapshot() on how to get a snapshot
     *     of the selection that will not reflect future changes
     *     to selection.
     *
     * @return The current seleciton.
     */
    public Selection getSelection() {
        return mSelection;
    }

    /**
     * Updates {@code dest} to reflect the current selection.
     * @param dest
     *
     * @return The Selection instance passed in, for convenience.
     */
    public Selection getSelection(Selection dest) {
        dest.copyFrom(mSelection);
        return dest;
    }

    /**
     * Causes item at {@code position} in adapter to be selected.
     *
     * @param position Adapter position
     * @param selected
     * @return True if the selection state of the item changed.
     */
    public boolean setItemSelected(int position, boolean selected) {
        boolean changed = (selected)
                ? mSelection.add(position)
                : mSelection.remove(position);

        if (changed) {
            notifyItemStateChanged(position, true);
        }
        return changed;
    }

    /**
     * @param position
     * @param length
     * @param selected
     * @return True if the selection state of any of the items changed.
     */
    public boolean setItemsSelected(int position, int length, boolean selected) {
        boolean changed = false;
        for (int i = position; i < position + length; i++) {
            changed |= setItemSelected(i, selected);
        }
        return changed;
    }

    /**
     * Clears the selection.
     */
    public void clearSelection() {
        mRanger = null;

        if (mSelection.isEmpty()) {
            return;
        }
        if (mIntermediateSelection == null) {
            mIntermediateSelection = new Selection();
        }
        getSelection(mIntermediateSelection);
        mSelection.clear();

        for (int i = 0; i < mIntermediateSelection.size(); i++) {
            int position = mIntermediateSelection.get(i);
            notifyItemStateChanged(position, false);
        }
    }

    /**
     * @param e
     * @return true if the event was consumed.
     */
    private boolean onSingleTapUp(MotionEvent e) {
        if (DEBUG) Log.d(TAG, "Handling tap event.");
        if (mSelection.isEmpty()) {
            return false;
        }

        return onSingleTapUp(mHelper.findEventPosition(e), e.getMetaState());
    }

    /**
     * TODO: Roll this into {@link #onSingleTapUp(MotionEvent)} once MotionEvent
     * can be mocked.
     *
     * @param position
     * @param metaState as returned from {@link MotionEvent#getMetaState()}.
     * @return true if the event was consumed.
     * @hide
     */
    @VisibleForTesting
    boolean onSingleTapUp(int position, int metaState) {
        if (mSelection.isEmpty()) {
            return false;
        }

        if (position == RecyclerView.NO_POSITION) {
            if (DEBUG) Log.d(TAG, "View is null. Canceling selection.");
            clearSelection();
            return true;
        }

        if (isShiftPressed(metaState) && mRanger != null) {
            mRanger.snapSelection(position);
        } else {
            toggleSelection(position);
        }
        return true;
    }

    private static boolean isShiftPressed(int metaState) {
        return (metaState & KeyEvent.META_SHIFT_ON) != 0;
    }

    private void onLongPress(MotionEvent e) {
        if (DEBUG) Log.d(TAG, "Handling long press event.");

        int position = mHelper.findEventPosition(e);
        if (position == RecyclerView.NO_POSITION) {
            if (DEBUG) Log.i(TAG, "View is null. Cannot handle tap event.");
        }

        onLongPress(position);
    }

    /**
     * TODO: Roll this back into {@link #onLongPress(MotionEvent)} once MotionEvent
     * can be mocked.
     *
     * @param position
     * @hide
     */
    @VisibleForTesting
    void onLongPress(int position) {
        if (position == RecyclerView.NO_POSITION) {
            if (DEBUG) Log.i(TAG, "View is null. Cannot handle tap event.");
        }

        toggleSelection(position);
    }

    /**
     * Toggles the selection state at position. If an item does end up selected
     * a new Ranger (range selection manager) at that point is created.
     *
     * @param position
     * @return True if state changed.
     */
    private boolean toggleSelection(int position) {
        // Position may be special "no position" during certain
        // transitional phases. If so, skip handling of the event.
        if (position == RecyclerView.NO_POSITION) {
            if (DEBUG) Log.d(TAG, "Ignoring toggle for element with no position.");
            return false;
        }

        if (mSelection.contains(position)) {
            return attemptDeselect(position);
        } else {
            boolean selected = attemptSelect(position);
            // Here we're already in selection mode. In that case
            // When a simple click/tap (without SHIFT) creates causes
            // an item to be selected.
            // By recreating Ranger at this point, we allow the user to create
            // multiple separate contiguous ranges with SHIFT+Click & Click.
            if (selected) {
                mRanger = new Ranger(position);
            }
            return selected;
        }
    }

    /**
     * Try to select all elements in range. Not that callbacks can cancel selection
     * of specific items, so some or even all items may not reflect the desired
     * state after the update is complete.
     *
     * @param begin inclusive
     * @param end inclusive
     * @param selected
     */
    private void updateRange(int begin, int end, boolean selected) {
        checkState(end >= begin);
        if (DEBUG) Log.i(TAG, String.format("Updating range begin=%d, end=%d, selected=%b.", begin, end, selected));
        for (int i = begin; i <= end; i++) {
            if (selected) {
                attemptSelect(i);
            } else {
                attemptDeselect(i);
            }
        }
    }

    /**
     * @param position
     * @return True if the update was applied.
     */
    private boolean attemptSelect(int position) {
        if (notifyBeforeItemStateChange(position, true)) {
            mSelection.add(position);
            notifyItemStateChanged(position, true);
            if (DEBUG) Log.d(TAG, "Selection after select: " + mSelection);
            return true;
        } else {
            if (DEBUG) Log.d(TAG, "Select cancelled by listener.");
            return false;
        }
    }

    /**
     * @param position
     * @return True if the update was applied.
     */
    private boolean attemptDeselect(int position) {
        if (notifyBeforeItemStateChange(position, false)) {
            mSelection.remove(position);
            notifyItemStateChanged(position, false);
            if (DEBUG) Log.d(TAG, "Selection after deselect: " + mSelection);
            return true;
        } else {
            if (DEBUG) Log.d(TAG, "Select cancelled by listener.");
            return false;
        }
    }

    private boolean notifyBeforeItemStateChange(int position, boolean nextState) {
        int lastListener = mCallbacks.size() - 1;
        for (int i = lastListener; i > -1; i--) {
            if (!mCallbacks.get(i).onBeforeItemStateChange(position, nextState)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Notifies registered listeners when a selection changes.
     *
     * @param position
     * @param selected
     */
    private void notifyItemStateChanged(int position, boolean selected) {
        int lastListener = mCallbacks.size() - 1;
        for (int i = lastListener; i > -1; i--) {
            mCallbacks.get(i).onItemStateChanged(position, selected);
        }
        mAdapter.notifyItemChanged(position);
    }

    /**
     * Class providing support for managing range selections.
     */
    private final class Ranger {
        private static final int UNDEFINED = -1;

        final int mBegin;
        int mEnd = UNDEFINED;

        public Ranger(int begin) {
            if (DEBUG) Log.d(TAG, String.format("New Ranger(%d) created.", begin));
            mBegin = begin;
        }

        void snapSelection(int position) {
            checkState(mRanger != null);
            checkArgument(position != RecyclerView.NO_POSITION);

            if (mEnd == UNDEFINED || mEnd == mBegin) {
                // Reset mEnd so it can be established in establishRange.
                mEnd = UNDEFINED;
                establishRange(position);
            } else {
                reviseRange(position);
            }
        }

        private void establishRange(int position) {
            checkState(mRanger.mEnd == UNDEFINED);

            if (position == mBegin) {
                mEnd = position;
            }

            if (position > mBegin) {
                updateRange(mBegin + 1, position, true);
            } else if (position < mBegin) {
                updateRange(position, mBegin - 1, true);
            }

            mEnd = position;
        }

        private void reviseRange(int position) {
            checkState(mEnd != UNDEFINED);
            checkState(mBegin != mEnd);

            if (position == mEnd) {
                if (DEBUG) Log.i(TAG, "Skipping no-op revision click on mEndRange.");
            }

            if (mEnd > mBegin) {
                reviseAscendingRange(position);
            } else if (mEnd < mBegin) {
                reviseDescendingRange(position);
            }
            // the "else" case is covered by checkState at beginning of method.

            mEnd = position;
        }

        /**
         * Updates an existing ascending seleciton.
         * @param position
         */
        private void reviseAscendingRange(int position) {
            // Reducing or reversing the range....
            if (position < mEnd) {
                if (position < mBegin) {
                    updateRange(mBegin + 1, mEnd, false);
                    updateRange(position, mBegin -1, true);
                } else {
                    updateRange(position + 1, mEnd, false);
                }
            }

            // Extending the range...
            else if (position > mEnd) {
                updateRange(mEnd + 1, position, true);
            }
        }

        private void reviseDescendingRange(int position) {
            // Reducing or reversing the range....
            if (position > mEnd) {
                if (position > mBegin) {
                    updateRange(mEnd, mBegin - 1, false);
                    updateRange(mBegin + 1, position, true);
                } else {
                    updateRange(mEnd, position - 1, false);
                }
            }

            // Extending the range...
            else if (position < mEnd) {
                updateRange(position, mEnd - 1, true);
            }
        }
    }

    /**
     * Object representing the current selection. Provides read only access
     * public access, and private write access.
     */
    public static final class Selection {

        private SparseBooleanArray mSelection;

        public Selection() {
            mSelection = new SparseBooleanArray();
        }

        /**
         * @param position
         * @return true if the position is currently selected.
         */
        public boolean contains(int position) {
            return mSelection.get(position);
        }

        /**
         * Useful for iterating over selection. Please note that
         * iteration should be done over a copy of the selection,
         * not the live selection.
         *
         * @see #copyTo(MultiSelectManager.Selection)
         *
         * @param index
         * @return the position value stored at specified index.
         */
        public int get(int index) {
            return mSelection.keyAt(index);
        }

        /**
         * @return size of the selection.
         */
        public int size() {
            return mSelection.size();
        }

        /**
         * @return true if the selection is empty.
         */
        public boolean isEmpty() {
            return mSelection.size() == 0;
        }

        private boolean flip(int position) {
            if (contains(position)) {
                remove(position);
                return false;
            } else {
                add(position);
                return true;
            }
        }

        /** @hide */
        @VisibleForTesting
        boolean add(int position) {
            if (!mSelection.get(position)) {
                mSelection.put(position, true);
                return true;
            }
            return false;
        }

        /** @hide */
        @VisibleForTesting
        boolean remove(int position) {
            if (mSelection.get(position)) {
                mSelection.delete(position);
                return true;
            }
            return false;
        }

        /**
         * Adjusts the selection range to reflect the existence of newly inserted values at
         * the specified positions. This has the effect of adjusting all existing selected
         * positions within the specified range accordingly.
         *
         * @param startPosition
         * @param count
         * @hide
         */
        @VisibleForTesting
        void expand(int startPosition, int count) {
            checkState(startPosition >= 0);
            checkState(count > 0);

            for (int i = 0; i < mSelection.size(); i++) {
                int itemPosition = mSelection.keyAt(i);
                if (itemPosition >= startPosition) {
                    mSelection.setKeyAt(i, itemPosition + count);
                }
            }
        }

        /**
         * Adjusts the selection range to reflect the removal specified positions. This has
         * the effect of adjusting all existing selected positions within the specified range
         * accordingly.
         *
         * @param startPosition
         * @param count The length of the range to collapse. Must be greater than 0.
         * @hide
         */
        @VisibleForTesting
        void collapse(int startPosition, int count) {
            checkState(startPosition >= 0);
            checkState(count > 0);

            int endPosition = startPosition + count - 1;

            SparseBooleanArray newSelection = new SparseBooleanArray();
            for (int i = 0; i < mSelection.size(); i++) {
                int itemPosition = mSelection.keyAt(i);
                if (itemPosition < startPosition) {
                    newSelection.append(itemPosition, true);
                } else if (itemPosition > endPosition) {
                    newSelection.append(itemPosition - count, true);
                }
            }
            mSelection = newSelection;
        }

        /** @hide */
        @VisibleForTesting
        void clear() {
            mSelection.clear();
        }

        /** @hide */
        @VisibleForTesting
        void copyFrom(Selection source) {
            mSelection = source.mSelection.clone();
        }

        @Override
        public String toString() {
            if (size() <= 0) {
                return "size=0, items=[]";
            }

            StringBuilder buffer = new StringBuilder(mSelection.size() * 28);
            buffer.append(String.format("{size=%d, ", mSelection.size()));
            buffer.append("items=[");
            for (int i=0; i < mSelection.size(); i++) {
                if (i > 0) {
                    buffer.append(", ");
                }
                buffer.append(mSelection.keyAt(i));
            }
            buffer.append("]}");
            return buffer.toString();
        }

        @Override
        public int hashCode() {
            return mSelection.hashCode();
        }

        @Override
        public boolean equals(Object that) {
          if (this == that) {
              return true;
          }

          if (!(that instanceof Selection)) {
              return false;
          }

          return mSelection.equals(((Selection) that).mSelection);
        }
    }

    interface RecyclerViewHelper {
        int findEventPosition(MotionEvent e);
    }

    public interface Callback {
        /**
         * Called when an item is selected or unselected while in selection mode.
         *
         * @param position Adapter position of the item that was checked or unchecked
         * @param selected <code>true</code> if the item is now selected, <code>false</code>
         *                if the item is now unselected.
         */
        public void onItemStateChanged(int position, boolean selected);

        /**
         * @param position
         * @param selected
         * @return false to cancel the change.
         */
        public boolean onBeforeItemStateChange(int position, boolean selected);
    }

    /**
     * A composite {@code OnGestureDetector} that allows us to delegate unhandled
     * events to an outside party (presumably DirectoryFragment).
     */
    private static final class CompositeOnGestureListener implements OnGestureListener {

        private OnGestureListener[] mListeners;

        public CompositeOnGestureListener(OnGestureListener... listeners) {
            mListeners = listeners;
        }

        @Override
        public boolean onDown(MotionEvent e) {
            for (int i = 0; i < mListeners.length; i++) {
                if (mListeners[i].onDown(e)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onShowPress(MotionEvent e) {
            for (int i = 0; i < mListeners.length; i++) {
                mListeners[i].onShowPress(e);
            }
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            for (int i = 0; i < mListeners.length; i++) {
                if (mListeners[i].onSingleTapUp(e)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            for (int i = 0; i < mListeners.length; i++) {
                if (mListeners[i].onScroll(e1, e2, distanceX, distanceY)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            for (int i = 0; i < mListeners.length; i++) {
                mListeners[i].onLongPress(e);
            }
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            for (int i = 0; i < mListeners.length; i++) {
                if (mListeners[i].onFling(e1, e2, velocityX, velocityY)) {
                    return true;
                }
            }
            return false;
        }
    }
}
