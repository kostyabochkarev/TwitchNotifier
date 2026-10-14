package com.example.twitchnotifier

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChannelAdapter(
    private val onRemove: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private val items = mutableListOf<Channel>()

    fun submit(list: List<Channel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.chName)
        val remove: TextView = v.findViewById(R.id.chRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.displayName
        holder.remove.setOnClickListener { onRemove(ch) }
    }

    override fun getItemCount() = items.size
}
