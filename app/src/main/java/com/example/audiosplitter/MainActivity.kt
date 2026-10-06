package com.example.audiosplitter

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.*
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var pick: Button; private lateinit var split: Button; private lateinit var custom: Button
    private lateinit var smart: Button; private lateinit var status: TextView; private lateinit var progress: ProgressBar
    private lateinit var selected: TextView
    private var inputUri: Uri? = null
    private var inputFile: File? = null
    private var mode = Mode.DEFAULT
    private var partMinutes = 10; private var overlapSeconds = 30
    private val executor = Executors.newSingleThreadExecutor()
    private enum class Mode { DEFAULT, CUSTOM, SMART }

    override fun onCreate(b: Bundle?) { super.onCreate(b); buildUi() }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,28,28,28); layoutDirection=View.LAYOUT_DIRECTION_RTL }
        val title=TextView(this).apply { text="🎧 Audio Splitter"; textSize=28f; setPadding(0,0,0,18) }
        selected=TextView(this).apply { text="لم يتم اختيار ملف"; textSize=16f; setPadding(0,10,0,20) }
        pick=Button(this).apply { text="📂 اختيار ملف MP3 أو M4A"; setOnClickListener{ chooseFile() } }
        custom=Button(this).apply { text="⚙️ تقسيم مخصص"; setOnClickListener{ customDialog() } }
        smart=Button(this).apply { text="🤖 تقسيم ذكي"; setOnClickListener{ mode=Mode.SMART; status.text="الوضع الذكي: سيختار البرنامج إعدادًا مناسبًا حسب طول الملف" } }
        split=Button(this).apply { text="🚀 إنشاء الأجزاء"; isEnabled=false; setOnClickListener{ startSplit() } }
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progress=0; visibility=View.GONE }
        status=TextView(this).apply { text="الافتراضي: 10 دقائق + 30 ثانية تداخل"; textSize=15f; setPadding(0,18,0,8) }
        root.addView(title); root.addView(selected); root.addView(pick); root.addView(custom); root.addView(smart); root.addView(status); root.addView(progress); root.addView(split)
        setContentView(root)
    }

    private fun chooseFile(){
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="audio/*"; addCategory(Intent.CATEGORY_OPENABLE) },100)
    }

    override fun onActivityResult(req:Int,res:Int,data:Intent?){ super.onActivityResult(req,res,data); if(req==100 && res==RESULT_OK && data?.data!=null){
        inputUri=data.data; try { contentResolver.takePersistableUriPermission(data.data!!, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch(_:Exception){}
        selected.text="الملف: ${displayName(data.data!!)}"; split.isEnabled=true; mode=Mode.DEFAULT; status.text="الافتراضي: 10 دقائق + 30 ثانية تداخل"
    }}

    private fun displayName(uri:Uri):String{ var n:String?=null; contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{ if(it.moveToFirst()) n=it.getString(0) }; return n ?: (uri.lastPathSegment ?: "audio") }

    private fun customDialog(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(35,10,35,0)}
        val mins=EditText(this).apply{hint="مدة الجزء بالدقائق";inputType=2;setText(partMinutes.toString())}
        val overlap=EditText(this).apply{hint="التداخل بالثواني";inputType=2;setText(overlapSeconds.toString())}
        box.addView(mins);box.addView(overlap)
        AlertDialog.Builder(this).setTitle("⚙️ تقسيم مخصص").setView(box).setPositiveButton("حفظ"){_,_->
            partMinutes=(mins.text.toString().toIntOrNull()?:10).coerceAtLeast(1); overlapSeconds=(overlap.text.toString().toIntOrNull()?:30).coerceIn(0,partMinutes*60-1); mode=Mode.CUSTOM
            status.text="مخصص: $partMinutes دقيقة + $overlapSeconds ثانية تداخل"
        }.setNegativeButton("إلغاء",null).show()
    }

    private fun startSplit(){
        val uri=inputUri ?: return
        split.isEnabled=false; pick.isEnabled=false; progress.visibility=View.VISIBLE; progress.progress=0
        status.text="جاري تجهيز الملف…"
        executor.execute {
            try {
                val file=copyToCache(uri); inputFile=file
                val duration=durationMs(file)
                if(duration<=0) throw Exception("تعذر معرفة مدة الملف")
                val settings=chooseSettings(duration)
                val partMs=settings.first*60_000L; val overlapMs=settings.second*1000L
                val stepMs=partMs-overlapMs
                val total= if(duration<=partMs) 1 else ((duration-partMs + stepMs-1)/stepMs + 1).toInt()
                val outDir=File(getExternalFilesDir(null),"AudioSplitter/${file.nameWithoutExtension}_${System.currentTimeMillis()}").apply{mkdirs()}
                var index=0; var start=0L
                while(start<duration){
                    index++; val len=min(partMs,duration-start); val out=File(outDir,String.format("%s_%02d.mp3",file.nameWithoutExtension,index))
                    runSegment(file,out,start,len,file.extension.lowercase()=="mp3")
                    val p=(index*100/total).coerceAtMost(100); runOnUiThread{progress.progress=p;status.text="تم إنشاء الجزء $index من $total"}
                    if(start+len>=duration) break
                    start += stepMs
                }
                runOnUiThread{progress.progress=100;status.text="✅ اكتمل التقسيم\nالمجلد: ${outDir.absolutePath}"; showDone(outDir)}
            }catch(e:Exception){runOnUiThread{status.text="❌ ${e.message ?: "حدث خطأ"}"}}
            finally{runOnUiThread{split.isEnabled=true;pick.isEnabled=true}}
        }
    }

    private fun chooseSettings(duration:Long):Pair<Int,Int>{
        if(mode==Mode.CUSTOM) return partMinutes to overlapSeconds
        if(mode==Mode.SMART){ val mins=when{duration<=30*60_000L->8; duration<=90*60_000L->10; duration<=150*60_000L->15; else->20}; return mins to 30 }
        return 10 to 30
    }

    private fun copyToCache(uri:Uri):File{
        val ext=(displayName(uri).substringAfterLast('.',"m4a")).lowercase(); val f=File(cacheDir,"input_${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri).use{ins->FileOutputStream(f).use{outs->ins!!.copyTo(outs,1024*1024)}}; return f
    }

    private fun durationMs(f:File):Long{ val r=android.media.MediaMetadataRetriever(); r.setDataSource(f.absolutePath); val x=r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0; r.release(); return x }

    private fun runSegment(input:File, output:File, start:Long, len:Long, copyMp3:Boolean){
        val s=start/1000.0; val d=len/1000.0
        val codec=if(copyMp3) "-c:a copy" else "-c:a libmp3lame -q:a 0"
        val cmd="-hide_banner -loglevel error -y -ss %.3f -i \"%s\" -t %.3f -vn %s -map_metadata 0 \"%s\"".format(s,input.absolutePath,d,codec,output.absolutePath)
        val session=FFmpegKit.execute(cmd)
        if(!ReturnCode.isSuccess(session.returnCode)) throw Exception("FFmpeg فشل في إنشاء الجزء ${output.name}")
    }

    private fun showDone(dir:File){ AlertDialog.Builder(this).setTitle("✅ تم الانتهاء").setMessage("تم إنشاء الملفات بصيغة MP3 داخل:\n${dir.absolutePath}").setPositiveButton("حسنًا",null).show() }

    override fun onDestroy(){executor.shutdownNow();super.onDestroy()}
}
