package com.github.jing332.tts_server_android.ui.systts.replace

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.MenuCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.drake.brv.BindingAdapter
import com.drake.brv.listener.DefaultItemTouchCallback
import com.drake.brv.listener.ItemDifferCallback
import com.drake.brv.utils.linear
import com.drake.brv.utils.setup
import com.drake.net.utils.withMain
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.constant.KeyConst
import com.github.jing332.tts_server_android.data.appDb
import com.github.jing332.tts_server_android.data.entities.AbstractListGroup
import com.github.jing332.tts_server_android.data.entities.replace.GroupWithReplaceRule
import com.github.jing332.tts_server_android.data.entities.replace.ReplaceRule
import com.github.jing332.tts_server_android.data.entities.replace.ReplaceRuleGroup
import com.github.jing332.tts_server_android.databinding.SysttsListCustomGroupItemBinding
import com.github.jing332.tts_server_android.databinding.SysttsReplaceActivityBinding
import com.github.jing332.tts_server_android.databinding.SysttsReplaceRuleItemBinding
import com.github.jing332.tts_server_android.help.SysTtsConfig
import com.github.jing332.tts_server_android.service.systts.SystemTtsService
import com.github.jing332.tts_server_android.ui.view.AppDialogs
import com.github.jing332.tts_server_android.ui.view.MaterialTextInput
import com.github.jing332.tts_server_android.util.*
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

@Suppress("DEPRECATION")
class ReplaceManagerActivity : AppCompatActivity() {
    companion object {
        const val TAG = "ReplaceManagerActivity"
    }

    private val vm: ReplaceManagerViewModel by viewModels()
    private val binding: SysttsReplaceActivityBinding by lazy {
        SysttsReplaceActivityBinding.inflate(layoutInflater)
    }

    private lateinit var brv: BindingAdapter

    private var currentSearchTarget: SearchTargetFilter = SearchTargetFilter.Name

    @SuppressLint("NotifyDataSetChanged")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.toolbar.setNavigationOnClickListener { finish() }

        brv = binding.recyclerView.linear().setup {
            addType<GroupModel>(R.layout.base_list_group_item)
            addType<ItemModel>(R.layout.systts_replace_rule_item)
            onCreate {
                getBindingOrNull<SysttsListCustomGroupItemBinding>()?.apply {
                    itemView.clickWithThrottle {
                        val data = getModel<GroupModel>().data
                        val isExpanded = !data.isExpanded
                        appDb.replaceRuleDao.insertGroup(data.copy(isExpanded = isExpanded))
                        getModel<GroupModel>().let { model ->
                            val enabledCount =
                                model.itemSublist?.filter { (it as ItemModel).data.isEnabled }?.size
                            val speakText =
                                if (isExpanded) R.string.group_expanded else R.string.group_collapsed
                            itemView.announceForAccessibility(
                                getString(speakText) + ", ${
                                    getString(
                                        R.string.systts_desc_list_count_info,
                                        model.itemSublist?.size,
                                        enabledCount
                                    )
                                }"
                            )

                            if (model.itemExpand && model.itemSublist.isNullOrEmpty()) {
                                collapse(modelPosition)
                                MaterialAlertDialogBuilder(this@ReplaceManagerActivity)
                                    .setTitle(R.string.msg_group_is_empty)
                                    .setMessage(getString(R.string.systts_group_empty_msg))
                                    .show()
                            }
                        }
                    }
                    btnMore.clickWithThrottle {
                        displayGroupMenu(
                            it, getModel()
                        )
                    }
                    checkBox.setOnClickListener {
                        val model = getModel<GroupModel>()
                        model.itemSublist?.forEach {
                            if (it is ItemModel) {
                                appDb.replaceRuleDao.update(it.data.copy(isEnabled = checkBox.isChecked))
                            }
                        }
                    }

                    checkBox.accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View,
                            info: AccessibilityNodeInfo
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            val str = when (checkBox.checkedState) {
                                MaterialCheckBox.STATE_CHECKED -> getString(R.string.md_checkbox_checked)
                                MaterialCheckBox.STATE_UNCHECKED -> getString(R.string.md_checkbox_unchecked)
                                MaterialCheckBox.STATE_INDETERMINATE -> getString(R.string.md_checkbox_indeterminate)
                                else -> ""
                            }
                            info.text = ", $str, "
                        }
                    }
                }

                getBindingOrNull<SysttsReplaceRuleItemBinding>()?.apply {
                    btnEdit.clickWithThrottle { edit(getModel<ItemModel>().data) }
                    btnMore.clickWithThrottle {
                        displayMoreMenu(it, getModel<ItemModel>().data)
                    }
                    checkBox.setOnClickListener {
                        appDb.replaceRuleDao.update(getModel<ItemModel>().data)
                    }
                }

            }

            itemDifferCallback = object : ItemDifferCallback {
                override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean {
                    return if (oldItem is ItemModel && newItem is ItemModel)
                        oldItem.data.id == newItem.data.id
                    else if (oldItem is GroupModel && newItem is GroupModel)
                        oldItem.data.id == newItem.data.id
                    else
                        false
                }

                override fun getChangePayload(oldItem: Any, newItem: Any) = true
            }

            itemTouchHelper = ItemTouchHelper(object : DefaultItemTouchCallback() {

                @Suppress("KotlinConstantConditions")
                override fun onSelectedChanged(
                    viewHolder: RecyclerView.ViewHolder?,
                    actionState: Int
                ) {
                    if (viewHolder != null
                        && getModel<Any>(viewHolder.layoutPosition) is GroupModel
                        && actionState == ItemTouchHelper.ACTION_STATE_DRAG
                    ) {
                        (viewHolder as BindingAdapter.BindingViewHolder).collapse()
                    }

                    super.onSelectedChanged(viewHolder, actionState)
                }

                override fun onDrag(
                    source: BindingAdapter.BindingViewHolder,
                    target: BindingAdapter.BindingViewHolder
                ) {
                    if (source.getModel<Any>() is GroupModel) {
                        models?.filterIsInstance<GroupModel>()?.let { models ->
                            models.forEachIndexed { index, value ->
                                appDb.replaceRuleDao.updateGroup(value.data.apply { order = index })
                            }
                        }
                    } else {
                        val groupModel =
                            models?.get(source.findParentPosition()) as GroupModel
                        val listInGroup =
                            models!!.filter { it is ItemModel && groupModel.data.id == it.data.groupId }
                        updateOrder(listInGroup)
                    }
                }
            })
        }
        binding.etSearch.addTextChangedListener {
            lifecycleScope.launch { updateListModels() }
        }
        binding.btnTarget.clickWithThrottle { view ->
            PopupMenu(this, view).apply {
                SearchTargetFilter.list.forEachIndexed { index, v ->
                    val item = menu.add(Menu.NONE, index, index, v.strId)
                    item.isCheckable = true
                    if (currentSearchTarget == v)
                        item.isChecked = true
                }

                setOnMenuItemClickListener { menuItem ->
                    SearchTargetFilter.list.forEachIndexed { index, _ ->
                        menu.findItem(index).isChecked = false
                    }
                    menuItem.isChecked = true
                    currentSearchTarget = SearchTargetFilter.list[menuItem.itemId]
                    lifecycleScope.launch { updateListModels() }
                    true
                }

                show()
            }
        }

        lifecycleScope.launch {
            appDb.replaceRuleDao.flowAllGroupWithReplaceRules().conflate().collect { list ->
                updateListModels(list)
            }
        }

        if (!SysTtsConfig.isReplaceEnabled) toast(R.string.systts_replace_please_on_switch)

        if (appDb.replaceRuleDao.getGroup() == null)
            appDb.replaceRuleDao.insertGroup(
                ReplaceRuleGroup(
                    id = AbstractListGroup.DEFAULT_GROUP_ID,
                    name = getString(
                        R.string.default_group
                    )
                )
            )
    }

    private fun displayMoreMenu(v: View, data: ReplaceRule) {
        PopupMenu(this, v).apply {
            setForceShowIcon(true)
            MenuCompat.setGroupDividerEnabled(menu, true)
            menuInflater.inflate(R.menu.systts_replace_rule_more, menu)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.menu_delete -> {
                        AppDialogs.displayDeleteDialog(this@ReplaceManagerActivity, data.name) {
                            appDb.replaceRuleDao.delete(data)
                        }
                    }
                    R.id.menu_move_to_top -> {
                        appDb.replaceRuleDao.updateDataAndRefreshOrder(data.copy(order = 0))
                    }
                    R.id.menu_move_to_bottom -> {
                        appDb.replaceRuleDao.updateDataAndRefreshOrder(data.copy(order = appDb.replaceRuleDao.count))
                    }
                }
                true
            }
        }.show()
    }

    private fun displayGroupMenu(v: View, model: GroupModel) {
        PopupMenu(this, v).apply {
            this.setForceShowIcon(true)
            MenuCompat.setGroupDividerEnabled(menu, true)
            menuInflater.inflate(R.menu.systts_list_group_more, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_export_config -> {
                        AppDialogs.displayExportDialog(
                            this@ReplaceManagerActivity,
                            lifecycleScope, vm.exportGroup(model)
                        )
                    }
                    R.id.menu_rename_group -> {
                        AppDialogs.displayInputDialog(
                            this@ReplaceManagerActivity,
                            getString(R.string.edit_group_name),
                            getString(R.string.name),
                            model.name
                        ) {
                            appDb.replaceRuleDao.insertGroup(model.data.copy(name = it))
                        }
                    }
                    R.id.menu_delete_group -> {
                        AppDialogs.displayDeleteDialog(this@ReplaceManagerActivity, model.name) {
                            appDb.replaceRuleDao.deleteGroup(model.data)
                            appDb.replaceRuleDao.deleteAllByGroup(model.data.id)
                        }
                    }
                }
                true
            }
            show()
        }

    }

    private var mLastModels: List<GroupWithReplaceRule>? = null

    private suspend fun updateListModels(list: List<GroupWithReplaceRule>? = mLastModels) {
        mLastModels = list
        mLastModels?.let { lastModels ->
            val filterText = binding.etSearch.text
            val models =
                lastModels.map { data ->
                    val filteredList = data.list.filter {
                        val pro = when (currentSearchTarget) {
                            SearchTargetFilter.Name -> it.name
                            SearchTargetFilter.Pattern -> it.pattern
                            SearchTargetFilter.Replacement -> it.replacement
                            SearchTargetFilter.Group -> appDb.replaceRuleDao.getGroup(it.groupId)?.name.toString()
                        }
                        pro.contains(filterText)
                    }.sortedBy { it.order }
                    val checkedState =
                        when (filteredList.filter { it.isEnabled }.size) {
                            0 -> MaterialCheckBox.STATE_UNCHECKED           // 全未选
                            filteredList.size -> MaterialCheckBox.STATE_CHECKED   // 全选
                            else -> MaterialCheckBox.STATE_INDETERMINATE    // 部分选
                        }

                    GroupModel(
                        data = data.group,
                        itemSublist = filteredList.map { ItemModel(it) },
                        itemExpand = filterText.isNotBlank() || data.group.isExpanded,
                        checkedState = checkedState
                    )
                }.filter { filterText.isEmpty() || it.itemSublist?.isNotEmpty() == true }

            if (brv.models == null) withMain { brv.models = models }
            else brv.setDifferModels(models)
        }
    }

    fun updateOrder(models: List<Any?>?) {
        models?.forEachIndexed { index, value ->
            val model = value as ItemModel
            appDb.replaceRuleDao.update(model.data.copy(order = index))
        }
    }

    private fun edit(data: ReplaceRule) {
        val intent = Intent(this, ReplaceRuleEditActivity::class.java)
        intent.putExtra(KeyConst.KEY_DATA, data)
        startForResult.launch(intent)
    }

    private val startForResult = registerForActivityResult(StartActivityForResult()) { result ->
        result.data?.apply {
            getParcelableExtra<ReplaceRule>(KeyConst.KEY_DATA)?.let {
                appDb.replaceRuleDao.insert(it)
                if (it.isEnabled) SystemTtsService.notifyUpdateConfig()
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (menu is MenuBuilder) menu.setOptionalIconsVisible(true)
        MenuCompat.setGroupDividerEnabled(menu, true)
        menuInflater.inflate(R.menu.systts_replace_manager, menu)

        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.menu_switch)?.let { menuItem ->
            menuItem.isChecked = SysTtsConfig.isReplaceEnabled
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> {
                val intent = Intent(this, ReplaceRuleEditActivity::class.java)
                startForResult.launch(intent)
            }
            R.id.menu_add_group -> {
                AppDialogs.displayInputDialog(
                    this,
                    getString(R.string.edit_group_name),
                    getString(R.string.name)
                ) { text ->
                    appDb.replaceRuleDao.insertGroup(ReplaceRuleGroup(name = text.ifEmpty {
                        getString(R.string.unnamed)
                    }))
                }
            }

            R.id.menu_switch -> {
                item.isChecked = !item.isChecked
                SysTtsConfig.isReplaceEnabled = item.isChecked
            }
            R.id.menu_importConfig -> {
                val et = MaterialTextInput(this)
                et.inputLayout.setHint(R.string.url_net)
                MaterialAlertDialogBuilder(this).setTitle(R.string.import_config).setView(et)
                    .setPositiveButton(R.string.import_from_clipboard) { _, _ ->
                        val err = vm.importConfig(ClipboardUtils.text.toString())
                        err?.let {
                            longToast("${getString(R.string.import_failed)}: $it")
                        }
                    }.setNegativeButton(R.string.import_from_url) { _, _ ->
                        val err = vm.importConfigFromUrl(et.inputEdit.text.toString())
                        err?.let {
                            longToast("${getString(R.string.import_failed)}: $it")
                        }
                    }.show()
            }

            R.id.menu_export_config -> {
                AppDialogs.displayExportDialog(this, lifecycleScope, vm.configToJson())
            }
        }

        return super.onOptionsItemSelected(item)
    }

    sealed class SearchTargetFilter(val strId: Int, val index: Int) {
        companion object {
            val list by lazy {
                listOf(
                    Name,
                    Pattern,
                    Replacement,
                    Group,
                )
            }
        }

        object Name : SearchTargetFilter(R.string.display_name, 0)
        object Pattern : SearchTargetFilter(R.string.systts_replace_rule, 1)
        object Replacement : SearchTargetFilter(R.string.systts_replace_as, 2)
        object Group : SearchTargetFilter(R.string.group, 3)
    }
}