package com.example.front.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.front.R;
import com.skt.Tmap.TMapPoint;
import com.skt.Tmap.poi_item.TMapPOIItem;

import java.util.ArrayList;
import java.util.List;

public class POIAdapter extends RecyclerView.Adapter<POIAdapter.POIViewHolder> {

    private List<TMapPOIItem> poiItems = new ArrayList<>();
    private OnItemClickListener listener;
    private TMapPoint currentLocation; // 현재 위치 추가

    public interface OnItemClickListener {
        void onItemClick(TMapPOIItem item);
    }

    public POIAdapter(OnItemClickListener listener) {
        this.listener = listener;
    }

    // 현재 위치 설정 메서드 추가
    public void setCurrentLocation(TMapPoint location) {
        this.currentLocation = location;
        notifyDataSetChanged(); // 거리 정보 업데이트를 위해 목록 갱신
    }

    public void setItems(List<TMapPOIItem> items) {
        this.poiItems = items;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public POIViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.search_result_item, parent, false);
        return new POIViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull POIViewHolder holder, int position) {
        TMapPOIItem item = poiItems.get(position);
        holder.bind(item, currentLocation, listener);
    }

    @Override
    public int getItemCount() {
        return poiItems.size();
    }

    static class POIViewHolder extends RecyclerView.ViewHolder {
        private TextView tvName, tvAddress, tvDistance;

        public POIViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_poi_name);
            tvAddress = itemView.findViewById(R.id.tv_poi_address);
            tvDistance = itemView.findViewById(R.id.tv_poi_distance);
        }

        public void bind(final TMapPOIItem item, TMapPoint currentLocation, final OnItemClickListener listener) {
            tvName.setText(item.getPOIName());
            tvAddress.setText(item.getPOIAddress());

            // 거리 정보 표시
            String distanceText = "거리 정보 없음";

            if (currentLocation != null) {
                // 현재 위치와 POI 사이의 거리 계산
                double distance = item.getDistance(currentLocation);

                if (distance > 0) {
                    if (distance >= 1000) {
                        distanceText = String.format("%.1f km", distance / 1000);
                    } else {
                        distanceText = String.format("%.0f m", distance);
                    }
                }
            }

            tvDistance.setText(distanceText);

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(item);
                }
            });
        }
    }
}