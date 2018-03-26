package com.uni.stuttgart.ipvs.androidgateway.helper;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.uni.stuttgart.ipvs.androidgateway.R;

import java.util.HashMap;
import java.util.List;

/**
 * Created by mdand on 2/19/2018.
 */

public class ExpandableListAdapter extends BaseExpandableListAdapter {

    private Context _context;
    private List<String> _listDataHeader; // header titles
    // child data in format of header title, child title
    private HashMap<String, List<String>> _listDataChild;
    private HashMap<String, String> _listDataHeaderSmall;
    private ImageViewClickListener clickListener;
    private String textAppearanceHeader;
    private boolean isWriteable;
    private int positionWriteable;

    public ExpandableListAdapter(Context context, List<String> listDataHeader,
                                 HashMap<String, List<String>> listChildData) {
        this._context = context;
        this._listDataHeader = listDataHeader;
        this._listDataChild = listChildData;
    }

    @Override
    public Object getChild(int groupPosition, int childPosititon) {
        return this._listDataChild.get(this._listDataHeader.get(groupPosition))
                .get(childPosititon);
    }

    @Override
    public long getChildId(int groupPosition, int childPosition) {
        return childPosition;
    }

    @Override
    public View getChildView(int groupPosition, final int childPosition,
                             boolean isLastChild, View convertView, ViewGroup parent) {

        final String childText = (String) getChild(groupPosition, childPosition);

        if (convertView == null) {
            LayoutInflater infalInflater = (LayoutInflater) this._context
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            convertView = infalInflater.inflate(R.layout.list_item, null);
        }

        TextView txtListChild = (TextView) convertView
                .findViewById(R.id.lblListItem);

        txtListChild.setText(childText);


       /* ImageView image = (ImageView) convertView.findViewById(R.id.imageWrite);
        if(isWriteable && positionWriteable == childPosition) {
            image.setVisibility(View.VISIBLE);
        } else {
            image.setVisibility(View.INVISIBLE);
        }*/

        return convertView;
    }

    @Override
    public int getChildrenCount(int groupPosition) {
        return this._listDataChild.get(this._listDataHeader.get(groupPosition))
                .size();
    }

    @Override
    public Object getGroup(int groupPosition) {
        return this._listDataHeader.get(groupPosition);
    }

    @Override
    public int getGroupCount() {
        return this._listDataHeader.size();
    }

    @Override
    public long getGroupId(int groupPosition) {
        return groupPosition;
    }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded,
                             View convertView, ViewGroup parent) {
        final String headerTitle = (String) getGroup(groupPosition);

        if (convertView == null) {
            LayoutInflater infalInflater = (LayoutInflater) this._context
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            convertView = infalInflater.inflate(R.layout.list_group, null);
        }

        TextView lblListHeader = (TextView) convertView
                .findViewById(R.id.lblListHeader);
        lblListHeader.setTypeface(null, Typeface.BOLD);
        lblListHeader.setText(headerTitle);

        TextView lblListHeaderSmall = (TextView) convertView
                .findViewById(R.id.lblListHeaderSmall);
        lblListHeaderSmall.setTypeface(null, Typeface.BOLD);
        lblListHeaderSmall.setText(_listDataHeaderSmall.get(headerTitle));

        ImageView image = (ImageView) convertView.findViewById(R.id.buttonRefresh);
        image.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.setTag(headerTitle);
                clickListener.imageViewListClicked(v);
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (textAppearanceHeader == "medium") {
                lblListHeader.setTextAppearance(android.R.style.TextAppearance_Medium);
            } else {
                lblListHeader.setTextAppearance(android.R.style.TextAppearance_Large);
            }
        }


        return convertView;
    }

    @Override
    public boolean hasStableIds() {
        return false;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
        return true;
    }

    public void setImageClickListener(ImageViewClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public void setTextAppearanceHeader(String textAppearance) {
        this.textAppearanceHeader = textAppearance;
    }

    public void setDataHeaderSmall(HashMap<String, String> stringListMap) {
        this._listDataHeaderSmall = stringListMap;
    }

    public void setChildDataWriteable(boolean writeable, int position) {
        this.isWriteable = writeable;
        this.positionWriteable = position;
    }

}