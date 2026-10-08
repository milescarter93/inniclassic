package com.themoon.y1.adapters;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.themoon.y1.MainActivity;
import com.themoon.y1.ThemeManager;
import com.themoon.y1.models.SongItem;

import java.io.File;
import java.util.List;

// 🚀 [Folders] The music folder browser as a recycling list: a folder like Music with thousands of files
// used to get one view per file (built 25 at a time), which took seconds and a lot of memory.
// Audio rows return a SongItem from getItem(), so hold Play/Pause opens the song menu on them.
public class FolderListAdapter extends BaseAdapter {
    public static final int PLAY_ALL = 0, FOLDER = 1, APK = 2, AUDIO = 3;

    public static final class Row {
        public final int type;
        public final File file;
        public final String label;
        final SongItem song;

        public Row(int type, File file, String label) {
            this.type = type;
            this.file = file;
            this.label = label;
            this.song = type == AUDIO ? new SongItem(file, label, "", "") : null;
        }
    }

    public interface OnRowClick {
        void onClick(Row row);
    }

    private final List<Row> rows;
    private final OnRowClick onRowClick;

    public FolderListAdapter(List<Row> rows, OnRowClick onRowClick) {
        this.rows = rows;
        this.onRowClick = onRowClick;
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Object getItem(int position) {
        Row row = rows.get(position);
        return row.type == AUDIO ? row.song : row;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final Row row = rows.get(position);
        String icon = row.type == FOLDER ? "" : row.type == AUDIO ? "" : "";
        final int color = row.type == APK ? 0xFF00FFFF : 0;

        View view;
        if (convertView == null) {
            view = MainActivity.instance.createListButtonWithIcon(icon, row.label, color);
            view.setLayoutParams(new AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT,
                    AbsListView.LayoutParams.WRAP_CONTENT));
        } else {
            view = convertView;
            LinearLayout layout = (LinearLayout) view;
            TextView tvIcon = (TextView) layout.getChildAt(0);
            TextView tvText = (TextView) layout.getChildAt(1);
            tvIcon.setText(icon);
            tvText.setText(row.label);
            int normal = color != 0 ? color : ThemeManager.getTextColorPrimary();
            if (!view.hasFocus()) {
                tvIcon.setTextColor(normal);
                tvText.setTextColor(normal);
            }
        }
        applyFocusListener(view, row.label, color);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.instance.clickFeedback();
                onRowClick.onClick(row);
            }
        });
        return view;
    }

    private void applyFocusListener(final View view, final String title, int customColor) {
        final int normal = customColor != 0 ? customColor : ThemeManager.getTextColorPrimary();
        view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                LinearLayout row = (LinearLayout) v;
                TextView tvIcon = (TextView) row.getChildAt(0);
                TextView tvText = (TextView) row.getChildAt(1);
                int textColor = hasFocus ? ThemeManager.getListButtonFocusedTextColor() : normal;
                view.setBackground(hasFocus ? MainActivity.instance.createFocusedButtonBackground()
                        : MainActivity.instance.createButtonBackground(ThemeManager.getListButtonNormalBg()));
                tvIcon.setTextColor(textColor);
                tvText.setTextColor(textColor);
                if (row.getChildCount() > 2) row.getChildAt(2).setVisibility(hasFocus ? View.VISIBLE : View.GONE);
                tvText.setSelected(hasFocus); // marquee for long names
                if (hasFocus) MainActivity.instance.showFastScrollLetter(title);
            }
        });
        boolean focused = view.hasFocus();
        view.setBackground(focused ? MainActivity.instance.createFocusedButtonBackground()
                : MainActivity.instance.createButtonBackground(ThemeManager.getListButtonNormalBg()));
    }
}
