package me.blog.korn123.easydiary.activities

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.RelativeLayout
import androidx.core.app.ActivityCompat
import com.github.amlcurran.showcaseview.ShowcaseView
import com.github.amlcurran.showcaseview.targets.ViewTarget
import com.github.ksoichiro.android.observablescrollview.ObservableListView
import io.github.aafactory.commons.utils.CommonUtils
import io.realm.Realm
import kotlinx.android.synthetic.main.activity_diary_main.*
import me.blog.korn123.commons.utils.EasyDiaryUtils
import me.blog.korn123.easydiary.R
import me.blog.korn123.easydiary.adapters.DiaryMainItemAdapter
import me.blog.korn123.easydiary.extensions.*
import me.blog.korn123.easydiary.helper.*
import me.blog.korn123.easydiary.models.DiaryDto
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import java.io.File
import java.util.*

/**
 * Created by CHO HANJOONG on 2017-03-16.
 */

class DiaryMainActivity : ToolbarControlBaseActivity<ObservableListView>() {

    /***************************************************************************************************
     *   global properties
     *
     ***************************************************************************************************/
    private var mRecognizerIntent: Intent? = null
    private var mDiaryMainItemAdapter: DiaryMainItemAdapter? = null
    private var mDiaryList: MutableList<DiaryDto>? = null
    private var mShowcaseIndex = 0
    private var mShowcaseView: ShowcaseView? = null


    /***************************************************************************************************
     *   override functions
     *
     ***************************************************************************************************/
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // android marshmallow minor version bug workaround
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.M) {
            Realm.init(this)
        }

        // application finish 확인insertDiaryButton
        if (intent.getBooleanExtra(APP_FINISH_FLAG, false)) {
            finish()
        }

        setSupportActionBar(toolbar)
        supportActionBar?.run {
            title = getString(R.string.read_diary_title)
        }

        mRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        mDiaryList = EasyDiaryDbHelper.readDiary(null)
        mDiaryList?.let {
            mDiaryMainItemAdapter = DiaryMainItemAdapter(this, R.layout.item_diary_main, it)
        }
        diaryListView.adapter = mDiaryMainItemAdapter

        if (!config.isInitDummyData) {
            initSampleData()
            config.isInitDummyData = true
        }

        EasyDiaryUtils.changeDrawableIconColor(this, config.textColor, R.drawable.delete)

        bindEvent()
        initShowcase()
        EasyDiaryUtils.initWorkingDirectory(this@DiaryMainActivity)
        migrateData()
        if (savedInstanceState == null) checkWhatsNewDialog()
    }

    override fun onResume() {
        super.onResume()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        refreshList()
        initTextSize(progressDialog, this)
        invalidateOptionsMenu()

        val previousActivity = config.previousActivity
        if (previousActivity == PREVIOUS_ACTIVITY_CREATE) {
//            diaryListView.smoothScrollToPosition(0)
            diaryListView.setSelection(0)
            config.previousActivity = -1
        }
    }

    override fun getLayoutResId(): Int {
        return R.layout.activity_diary_main
    }

    override fun createScrollable(): ObservableListView {
        // ObservableListView uses setOnScrollListener, but it still works.
        diaryListView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
            }

            override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
            }
        })
        return diaryListView
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_EXTERNAL_STORAGE -> if (checkPermission(EXTERNAL_STORAGE_PERMISSIONS)) {
                openPostcardViewer()
            } else {
                makeSnackBar(findViewById(android.R.id.content), getString(R.string.guide_message_3))
            }
            else -> {}
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_SPEECH_INPUT -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    query.setText(result[0])
                    query.setSelection(result[0].length)
                }
                pauseLock()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.settings -> {
                val settingIntent = Intent(this@DiaryMainActivity, SettingsActivity::class.java)
                //                startActivity(settingIntent);
                TransitionHelper.startActivityWithTransition(this@DiaryMainActivity, settingIntent)
            }
            R.id.chart -> {
                val chartIntent = Intent(this@DiaryMainActivity, StatisticsActivity::class.java)
                //                startActivity(chartIntent);
                TransitionHelper.startActivityWithTransition(this@DiaryMainActivity, chartIntent)
            }
            R.id.timeline -> {
                val timelineIntent = Intent(this@DiaryMainActivity, TimelineActivity::class.java)
                //                startActivity(timelineIntent);
                TransitionHelper.startActivityWithTransition(this@DiaryMainActivity, timelineIntent)
            }
            R.id.planner -> {
                val calendarIntent = Intent(this@DiaryMainActivity, CalendarActivity::class.java)
                //                startActivity(calendarIntent);
                TransitionHelper.startActivityWithTransition(this@DiaryMainActivity, calendarIntent)
            }
            R.id.microphone -> showSpeechDialog()
            R.id.postCard -> {
                when (checkPermission(EXTERNAL_STORAGE_PERMISSIONS)) {
                    true -> openPostcardViewer()
                    false -> {
                        confirmPermission(EXTERNAL_STORAGE_PERMISSIONS, REQUEST_CODE_EXTERNAL_STORAGE)
                    }
                }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.diary_main, menu)
        val targetItems = mutableListOf<MenuItem>()
        targetItems.add(menu.findItem(R.id.chart))
        targetItems.add(menu.findItem(R.id.settings))
        targetItems.add(menu.findItem(R.id.postCard))
        targetItems.map { item ->
            applyFontToMenuItem(item)
        }
        
        return true
    }

    override fun onBackPressed() {
        if (progressDialog.visibility == View.GONE) ActivityCompat.finishAffinity(this@DiaryMainActivity)
    }


    /***************************************************************************************************
     *   etc functions
     *
     ***************************************************************************************************/    
    private fun openPostcardViewer() {
        val postCardViewer = Intent(this@DiaryMainActivity, PostCardViewerActivity::class.java)
        TransitionHelper.startActivityWithTransition(this@DiaryMainActivity, postCardViewer)
    }

    private fun migrateData() {
        Thread(Runnable {
            val listPhotoUri = EasyDiaryDbHelper.selectPhotoUriAll()
            var isFontDirMigrate = false
            for ((index, dto) in listPhotoUri.withIndex()) {
//                Log.i("PHOTO-URI", dto.photoUri)
                if (dto.isContentUri()) {
                    val photoPath = EasyDiaryUtils.getApplicationDataDirectory(this) + DIARY_PHOTO_DIRECTORY + UUID.randomUUID().toString()
                    CommonUtils.uriToFile(this, Uri.parse(dto.photoUri), photoPath)
                    EasyDiaryDbHelper.getInstance().beginTransaction()
                    dto.photoUri = FILE_URI_PREFIX + photoPath
                    EasyDiaryDbHelper.getInstance().commitTransaction()
                    runOnUiThread {
                        progressInfo.text = "Converting... ($index/${listPhotoUri.size})"
                    }
                }
            }

            if (checkPermission(EXTERNAL_STORAGE_PERMISSIONS)) {
                File(EasyDiaryUtils.getApplicationDataDirectory(this) + WORKING_DIRECTORY).listFiles()?.let {
                    it.forEach { file ->
                        if (file.extension.equals("jpg", true)) FileUtils.moveFileToDirectory(file, File(EasyDiaryUtils.getApplicationDataDirectory(this) + DIARY_POSTCARD_DIRECTORY), true)
                    }
                }

                // Move attached photo from external storage to application data directory
                // From 1.4.102
                // 01. DIARY_PHOTO_DIRECTORY
                val photoSrcDir = File(EasyDiaryUtils.getExternalStorageDirectory(), DIARY_PHOTO_DIRECTORY)
                val photoDestDir = File(EasyDiaryUtils.getApplicationDataDirectory(this) + DIARY_PHOTO_DIRECTORY)
                photoSrcDir.listFiles()?.let {
                    it.forEachIndexed { index, file ->
                        Log.i("aaf-t", "${File(photoDestDir, file.name).exists()} ${File(photoDestDir, file.name).absolutePath}")
                        if (File(photoDestDir, file.name).exists()) {
                            Log.i("aaf-t", "${File(photoDestDir, file.name).delete()}")
                        }
                        FileUtils.copyFileToDirectory(file, photoDestDir)
                        runOnUiThread {
                            migrationMessage.text = getString(R.string.storage_migration_message)
                            progressInfo.text = "$index/${it.size} (Photo)"
                        }
                    }
                    photoSrcDir.renameTo(File(photoSrcDir.absolutePath + "_migration"))
                }
//                destDir.listFiles().map { file ->
//                    FileUtils.moveToDirectory(file, srcDir, true)
//                }

                // 02. DIARY_POSTCARD_DIRECTORY
                val postCardSrcDir = File(EasyDiaryUtils.getExternalStorageDirectory(), DIARY_POSTCARD_DIRECTORY)
                val postCardDestDir = File(EasyDiaryUtils.getApplicationDataDirectory(this) + DIARY_POSTCARD_DIRECTORY)
                postCardSrcDir.listFiles()?.let {
                    it.forEachIndexed { index, file ->
                        if (File(postCardDestDir, file.name).exists()) {
                            File(postCardDestDir, file.name).delete()
                        }
                        FileUtils.copyFileToDirectory(file, postCardDestDir)
                        runOnUiThread {
                            progressInfo.text = "$index/${it.size} (Postcard)"
                        }
                    }
                    postCardSrcDir.renameTo(File(postCardSrcDir.absolutePath + "_migration"))
                }

                // 03. USER_CUSTOM_FONTS_DIRECTORY
                val fontSrcDir = File(EasyDiaryUtils.getExternalStorageDirectory(), USER_CUSTOM_FONTS_DIRECTORY)
                val fontDestDir = File(EasyDiaryUtils.getApplicationDataDirectory(this) + USER_CUSTOM_FONTS_DIRECTORY)
                fontSrcDir.listFiles()?.let {
                    it.forEachIndexed { index, file ->
                        if (File(fontDestDir, file.name).exists()) {
                            File(fontDestDir, file.name).delete()
                        }
                        FileUtils.copyFileToDirectory(file, fontDestDir)
                        runOnUiThread {
                            progressInfo.text = "$index/${it.size} (Font)"
                        }
                    }
                    fontSrcDir.renameTo(File(fontSrcDir.absolutePath + "_migration"))
                    if (it.isNotEmpty()) isFontDirMigrate = true
                }

                // 04. BACKUP_DB_DIRECTORY
                val dbSrcDir = File(EasyDiaryUtils.getExternalStorageDirectory(), BACKUP_DB_DIRECTORY)
                val dbDestDir = File(EasyDiaryUtils.getApplicationDataDirectory(this) + BACKUP_DB_DIRECTORY)
                dbSrcDir.listFiles()?.let {
                    it.forEachIndexed { index, file ->
                        if (File(dbDestDir, file.name).exists()) {
                            File(dbDestDir, file.name).delete()
                        }
                        FileUtils.copyFileToDirectory(file, dbDestDir)
                        runOnUiThread {
                            progressInfo.text = "$index/${it.size} (Database)"
                        }
                    }
                    dbSrcDir.renameTo(File(dbSrcDir.absolutePath + "_migration"))
                }
            }

            runOnUiThread {
                progressDialog.visibility = View.GONE
                modalContainer.visibility = View.GONE
                if (isFontDirMigrate) {
                    showAlertDialog("Font 리소스가 변경되어 애플리케이션을 다시 시작합니다.", DialogInterface.OnClickListener { _, _ ->
                        restartApp()
                    }, false)
                }
            }
        }).start()
    }
    
    private fun initShowcase() {
        val margin = ((resources.displayMetrics.density * 12) as Number).toInt()

        val centerParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        centerParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        centerParams.addRule(RelativeLayout.CENTER_HORIZONTAL)
        centerParams.setMargins(0, 0, 0, margin)

        val leftParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        leftParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        leftParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT)
        leftParams.setMargins(margin, margin, margin, margin)
        val showcaseViewOnClickListener = View.OnClickListener {
            mShowcaseView?.run {
                when (mShowcaseIndex) {
                    0 -> {
                        setButtonPosition(centerParams)
                        setShowcase(ViewTarget(query), true)
                        setContentTitle(getString(R.string.read_diary_showcase_title_2))
                        setContentText(getString(R.string.read_diary_showcase_message_2))
                    }
                    1 -> {
                        setButtonPosition(centerParams)
                        setShowcase(ViewTarget(diaryListView), true)
                        setContentTitle(getString(R.string.read_diary_showcase_title_8))
                        setContentText(getString(R.string.read_diary_showcase_message_8))
                    }
                    2 -> {
                        setButtonPosition(centerParams)
                        setTarget(ViewTarget(R.id.planner, this@DiaryMainActivity))
                        setContentTitle(getString(R.string.read_diary_showcase_title_4))
                        setContentText(getString(R.string.read_diary_showcase_message_4))
                    }
                    3 -> {
                        setButtonPosition(centerParams)
                        setTarget(ViewTarget(R.id.timeline, this@DiaryMainActivity))
                        setContentTitle(getString(R.string.read_diary_showcase_title_5))
                        setContentText(getString(R.string.read_diary_showcase_message_5))
                    }
                    4 -> {
                        setButtonPosition(centerParams)
                        setTarget(ViewTarget(R.id.microphone, this@DiaryMainActivity))
                        setContentTitle(getString(R.string.read_diary_showcase_title_3))
                        setContentText(getString(R.string.read_diary_showcase_message_3))
                    }
                    5 -> hide()
                }
            }
            mShowcaseIndex++
        }

        mShowcaseView = ShowcaseView.Builder(this)
                .withMaterialShowcase()
                .setTarget(ViewTarget(insertDiaryButton))
                .setContentTitle(getString(R.string.read_diary_showcase_title_1))
                .setContentText(getString(R.string.read_diary_showcase_message_1))
                .setStyle(R.style.ShowcaseTheme)
                .singleShot(SHOWCASE_SINGLE_SHOT_READ_DIARY_NUMBER.toLong())
                .setOnClickListener(showcaseViewOnClickListener)
                .build()
        mShowcaseView?.setButtonText(getString(R.string.read_diary_showcase_button_1))
        mShowcaseView?.setButtonPosition(centerParams)
    }

    private fun bindEvent() {
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}

            override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {
                refreshList(charSequence.toString())
            }

            override fun afterTextChanged(editable: Editable) {}
        })

        clearQuery.setOnClickListener { _ -> query.setText(null) }

        diaryListView.onItemClickListener = AdapterView.OnItemClickListener { adapterView, view, i, l ->
            val diaryDto = adapterView.adapter.getItem(i) as DiaryDto
            val detailIntent = Intent(this@DiaryMainActivity, DiaryReadActivity::class.java)
            detailIntent.putExtra(DIARY_SEQUENCE, diaryDto.sequence)
            detailIntent.putExtra(DIARY_SEARCH_QUERY, mDiaryMainItemAdapter?.currentQuery)
            TransitionHelper.startActivityWithTransition(this@DiaryMainActivity, detailIntent)
        }

        diaryListView.setOnItemLongClickListener { adapterView, _, i, _ ->
            val diaryDto = adapterView.adapter.getItem(i) as DiaryDto
            showAlertDialog(getString(R.string.copy_diary_item),
                    DialogInterface.OnClickListener { _, _ ->
                        val copyItem = EasyDiaryDbHelper.getInstance().copyFromRealm(diaryDto)
                        copyItem.currentTimeMillis = System.currentTimeMillis()
                        copyItem.updateDateString()
                        EasyDiaryDbHelper.insertDiary(copyItem)
                        refreshList()
                        Handler().post { diaryListView.setSelection(0) }
                    },
                    null
            )
            true
        }

        modalContainer.setOnTouchListener { _, _ -> true }

        insertDiaryButton.setOnClickListener{
            val createDiary = Intent(this@DiaryMainActivity, DiaryInsertActivity::class.java)
            //                startActivity(createDiary);
            //                DiaryMainActivity.this.overridePendingTransition(R.anim.anim_right_to_center, R.anim.anim_center_to_left);
            TransitionHelper.startActivityWithTransition(this@DiaryMainActivity, createDiary)
        }
    }

    private fun showSpeechDialog() {
        try {
            startActivityForResult(mRecognizerIntent, REQUEST_CODE_SPEECH_INPUT)
        } catch (e: ActivityNotFoundException) {
            showAlertDialog(getString(R.string.recognizer_intent_not_found_message), DialogInterface.OnClickListener { dialog, which -> })
        }
    }

    private fun refreshList() {
        var queryString = ""
        if (StringUtils.isNotEmpty(query.text)) queryString = query.text.toString()
        refreshList(queryString)
    }

    fun refreshList(query: String) {
        mDiaryList?.clear()
        mDiaryList?.addAll(EasyDiaryDbHelper.readDiary(query, config.diarySearchQueryCaseSensitive))
        mDiaryMainItemAdapter?.currentQuery = query
        mDiaryMainItemAdapter?.notifyDataSetChanged()
    }

    private fun initSampleData() {
        EasyDiaryDbHelper.insertDiary(DiaryDto(
                -1,
                System.currentTimeMillis() - 395000000L, getString(R.string.sample_diary_title_1), getString(R.string.sample_diary_1),
                1
        ))
        EasyDiaryDbHelper.insertDiary(DiaryDto(
                -1,
                System.currentTimeMillis() - 263000000L, getString(R.string.sample_diary_title_2), getString(R.string.sample_diary_2),
                2
        ))
        EasyDiaryDbHelper.insertDiary(DiaryDto(
                -1,
                System.currentTimeMillis() - 132000000L, getString(R.string.sample_diary_title_3), getString(R.string.sample_diary_3),
                3
        ))
        EasyDiaryDbHelper.insertDiary(DiaryDto(
                -1,
                System.currentTimeMillis() - 4000000L, getString(R.string.sample_diary_title_4), getString(R.string.sample_diary_4),
                4
        ))
    }
}
