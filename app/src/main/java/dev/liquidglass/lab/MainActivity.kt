package dev.liquidglass.lab

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.os.Bundle
import android.os.Build
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.BitmapShader
import android.graphics.Shader
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Camera
import androidx.core.content.ContextCompat
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.exifinterface.media.ExifInterface
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import org.json.JSONObject
import org.json.JSONArray
import androidx.compose.ui.layout.onSizeChanged

private data class WatermarkSnapshot(
    val title:String,val subtitle:String,val titleSize:Float,val blur:Float,val refract:Float,val radius:Float,val opacity:Float,
    val offsetX:Float,val offsetY:Float,val width:Float,val height:Float,val padding:Float,val rotation:Float,val centered:Boolean,val locked:Boolean,
    val clones:List<Offset> = emptyList(),val texts:List<FreeText> = emptyList()
)
private data class FreeText(val id:Long,val text:String,val x:Float,val y:Float,val size:Float,val rotation:Float,val scale:Float=1f)

private data class CardSnapshot(
    val name:String,val detail:String,val payload:String,val textX:Float,val textY:Float,val qrX:Float,val qrY:Float,
    val textSize:Float,val qrSize:Float,val radius:Float,val blur:Float,val refract:Float,val opacity:Float,
    val textLocked:Boolean,val qrLocked:Boolean,val qrOnTop:Boolean
)

private class EditHistory<T>(initial:T) {
    val undo=mutableStateListOf<T>(); val redo=mutableStateListOf<T>(); var current by mutableStateOf(initial)
    fun edit(next:T){if(next==current)return;undo.add(current);if(undo.size>60)undo.removeAt(0);redo.clear();current=next}
    fun checkpoint(){if(undo.lastOrNull()!=current){undo.add(current);if(undo.size>60)undo.removeAt(0)};redo.clear()}
    fun live(next:T){current=next}
    fun undo(){if(undo.isNotEmpty()){redo.add(current);current=undo.removeAt(undo.lastIndex)}}
    fun redo(){if(redo.isNotEmpty()){undo.add(current);current=redo.removeAt(redo.lastIndex)}}
}

private fun WatermarkSnapshot.toJson()=JSONObject().apply {
    put("glassTextV2",true);put("title",title);put("subtitle",subtitle);put("titleSize",titleSize);put("blur",blur);put("refract",refract);put("radius",radius);put("opacity",opacity);put("offsetX",offsetX);put("offsetY",offsetY);put("width",width);put("height",height);put("padding",padding);put("rotation",rotation);put("centered",centered);put("locked",locked);put("clones",JSONArray().apply{clones.forEach{put(JSONObject().put("x",it.x).put("y",it.y))}});put("texts",JSONArray().apply{texts.forEach{t->put(JSONObject().put("id",t.id).put("text",t.text).put("x",t.x).put("y",t.y).put("size",t.size).put("rotation",t.rotation).put("scale",t.scale))}})
}.toString()
private fun watermarkFromJson(s:String)=JSONObject(s).run {
    val a=optJSONArray("clones");val cloneList=buildList{if(a!=null)for(i in 0 until a.length()){val o=a.getJSONObject(i);add(Offset(o.getDouble("x").toFloat(),o.getDouble("y").toFloat()))}}
    val glassTextV2=optBoolean("glassTextV2",false);val oldX=optDouble("offsetX",0.0).toFloat();val oldY=optDouble("offsetY",0.0).toFloat()
    val ta=optJSONArray("texts");val textList=buildList{
        if(!glassTextV2){
            val size=optDouble("titleSize",18.0).toFloat();val centered=optBoolean("centered",false);val baseX=if(centered)0f else -42f
            optString("title").takeIf{it.isNotBlank()}?.let{add(FreeText(-1L,it,baseX,-16f,size,0f))}
            optString("subtitle").takeIf{it.isNotBlank()}?.let{add(FreeText(-2L,it,baseX,15f,size*.67f,0f))}
        }
        if(ta!=null)for(i in 0 until ta.length()){val o=ta.getJSONObject(i);add(FreeText(o.getLong("id"),o.getString("text"),o.getDouble("x").toFloat()-(if(glassTextV2)0f else oldX),o.getDouble("y").toFloat()-(if(glassTextV2)0f else oldY),o.getDouble("size").toFloat(),o.optDouble("rotation",0.0).toFloat(),o.optDouble("scale",1.0).toFloat()))}
    }
    WatermarkSnapshot(getString("title"),getString("subtitle"),getDouble("titleSize").toFloat(),getDouble("blur").toFloat(),getDouble("refract").toFloat(),getDouble("radius").toFloat(),getDouble("opacity").toFloat(),getDouble("offsetX").toFloat(),getDouble("offsetY").toFloat(),getDouble("width").toFloat(),getDouble("height").toFloat(),getDouble("padding").toFloat(),getDouble("rotation").toFloat(),getBoolean("centered"),optBoolean("locked",false),cloneList,textList)
}
private fun CardSnapshot.toJson()=JSONObject().apply {
    put("name",name);put("detail",detail);put("payload",payload);put("textX",textX);put("textY",textY);put("qrX",qrX);put("qrY",qrY);put("textSize",textSize);put("qrSize",qrSize);put("radius",radius);put("blur",blur);put("refract",refract);put("opacity",opacity);put("textLocked",textLocked);put("qrLocked",qrLocked);put("qrOnTop",qrOnTop)
}.toString()
private fun cardFromJson(s:String)=JSONObject(s).run { CardSnapshot(getString("name"),getString("detail"),getString("payload"),getDouble("textX").toFloat(),getDouble("textY").toFloat(),getDouble("qrX").toFloat(),getDouble("qrY").toFloat(),getDouble("textSize").toFloat(),getDouble("qrSize").toFloat(),getDouble("radius").toFloat(),getDouble("blur").toFloat(),getDouble("refract").toFloat(),getDouble("opacity").toFloat(),optBoolean("textLocked",false),optBoolean("qrLocked",false),optBoolean("qrOnTop",true)) }

private fun templatePrefs(context:android.content.Context)=context.getSharedPreferences("editor_templates",android.content.Context.MODE_PRIVATE)
private fun saveTemplate(context:android.content.Context,type:String,name:String,json:String)=templatePrefs(context).edit().putString("$type:$name",json).apply()
private fun loadTemplates(context:android.content.Context,type:String)=templatePrefs(context).all.filterKeys{it.startsWith("$type:")}.mapKeys{it.key.substringAfter(':')}.mapValues{it.value as String}.toSortedMap()

private data class LoadedImage(val bitmap: ImageBitmap) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
    val aspect: Float get() = width.toFloat() / height.toFloat()
}

private fun loadImage(context: android.content.Context, uri: Uri): LoadedImage? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
        LoadedImage(android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE }.asImageBitmap())
    } else {
        val decoded = context.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
        val orientation = context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        val degrees = when (orientation) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
        val corrected = if (degrees == 0f) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
        LoadedImage(corrected.asImageBitmap())
    }
}.getOrNull()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migrateTestArtifacts()
        enableEdgeToEdge()
        setContent { GlassLabTheme { GlassLab() } }
    }

    private fun migrateTestArtifacts() {
        val prefs=getSharedPreferences("editor_templates",MODE_PRIVATE)
        val edit=prefs.edit()
        if(!prefs.getBoolean("cleanup_e2e_v1",false)){
            prefs.getString("draft:watermark",null)?.let { raw -> runCatching {
                val json=JSONObject(raw);val clones=json.optJSONArray("clones")
                val knownTestState=json.optBoolean("locked") && clones?.length()==1 && json.optString("title")=="SHOT ON LIQUID"
                if(knownTestState){json.put("locked",false);json.put("clones",JSONArray());edit.putString("draft:watermark",json.toString())}
            }}
            prefs.getString("draft:card",null)?.let { raw -> runCatching {
                val json=JSONObject(raw);if(json.optBoolean("textLocked")&&json.optString("name")=="LIQUID MAKER"){json.put("textLocked",false);json.put("qrLocked",false);json.put("textX",-70);json.put("textY",0);json.put("qrX",110);json.put("qrY",0);edit.putString("draft:card",json.toString())}
            }}
            edit.remove("watermark:我的模板").putBoolean("cleanup_e2e_v1",true)
        }
        if(!prefs.getBoolean("logical_coords_v1",false)){
            val screenDensity=resources.displayMetrics.density.coerceAtLeast(1f)
            prefs.getString("draft:watermark",null)?.let{raw->runCatching{val j=JSONObject(raw);j.put("offsetX",j.optDouble("offsetX")/screenDensity);j.put("offsetY",j.optDouble("offsetY")/screenDensity);val a=j.optJSONArray("clones");if(a!=null)for(i in 0 until a.length()){val o=a.getJSONObject(i);o.put("x",o.optDouble("x")/screenDensity);o.put("y",o.optDouble("y")/screenDensity)};edit.putString("draft:watermark",j.toString())}}
        }
        edit.putBoolean("logical_coords_v1",true).apply()
    }
}

private enum class LabPage(val title: String) { Playground("实验台"), Watermark("铭牌"), Camera("相机"), Card("名片") }

@Composable
private fun GlassLab() {
    val context=LocalContext.current
    val sessionPrefs=remember{context.getSharedPreferences("session",android.content.Context.MODE_PRIVATE)}
    var page by remember { mutableStateOf(runCatching{LabPage.valueOf(sessionPrefs.getString("page",LabPage.Playground.name)!!)}.getOrDefault(LabPage.Playground)) }
    Scaffold(
        containerColor = Color(0xFF101016),
        bottomBar = {
            NavigationBar(containerColor = Color(0xEE181820)) {
                LabPage.entries.forEach { item ->
                    NavigationBarItem(
                        selected = page == item, onClick = { page = item;sessionPrefs.edit().putString("page",item.name).apply() },
                        icon = { Icon(when (item) { LabPage.Playground -> Icons.Rounded.Tune; LabPage.Watermark -> Icons.Rounded.Photo; LabPage.Camera -> Icons.Rounded.CameraAlt; LabPage.Card -> Icons.Rounded.QrCode }, null) },
                        label = { Text(item.title) }
                    )
                }
            }
        }
    ) { padding ->
        when (page) {
            LabPage.Playground -> Playground(Modifier.padding(padding))
            LabPage.Watermark -> WatermarkLab(Modifier.padding(padding))
            LabPage.Camera -> CameraLab(Modifier.padding(padding))
            LabPage.Card -> CardLab(Modifier.padding(padding))
        }
    }
}

@Composable
private fun Playground(modifier: Modifier = Modifier) {
    var blur by remember { mutableFloatStateOf(4f) }
    var refraction by remember { mutableFloatStateOf(.30f) }
    var edge by remember { mutableFloatStateOf(.22f) }
    var chromatic by remember { mutableStateOf(true) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    GlassStage(modifier, controls = { backdrop ->
        Box(
            Modifier.align(Alignment.Center).offset { androidx.compose.ui.unit.IntOffset(offset.x.toInt(), offset.y.toInt()) }
                .size(230.dp, 150.dp)
                .drawBackdrop(
                    backdrop = backdrop, shape = { RoundedRectangle(42.dp) },
                    effects = {
                        vibrancy(); blur(blur.dp.toPx())
                        lens(size.minDimension * edge, size.minDimension * refraction, depthEffect = true, chromaticAberration = chromatic)
                    },
                    highlight = { Highlight.Plain },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = .13f)) }
                )
                .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); offset += drag } }
        ) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("LIQUID GLASS", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("拖动我，观察背景折射", color = Color.White.copy(.78f), fontSize = 13.sp)
            }
        }
    }) { backdrop ->
        ControlSheet(backdrop, blur, { blur = it }, refraction, { refraction = it }, edge, { edge = it }, chromatic, { chromatic = it })
    }
}

@Composable
private fun GlassStage(
    modifier: Modifier,
    controls: @Composable BoxScope.(LayerBackdrop) -> Unit,
    bottom: @Composable BoxScope.(LayerBackdrop) -> Unit
) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()?.let { image = it }
    }
    val backdrop = rememberLayerBackdrop()
    Box(modifier.fillMaxSize()) {
        if (image != null) {
            Image(BitmapPainter(image!!), null, Modifier.fillMaxSize().layerBackdrop(backdrop), contentScale = ContentScale.Crop)
        } else {
            DemoBackdrop(Modifier.fillMaxSize().layerBackdrop(backdrop))
        }
        controls(backdrop)
        Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(20.dp)) {
            Text("柔光玻璃实验室", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Rounded.AddPhotoAlternate, null); Spacer(Modifier.width(6.dp)); Text("换一张背景")
            }
        }
        bottom(backdrop)
    }
}

@Composable
private fun DemoBackdrop(modifier: Modifier) {
    Canvas(modifier.background(Color(0xFF162239))) {
        drawCircle(Color(0xFFFFA94D), size.minDimension * .45f, Offset(size.width * .18f, size.height * .25f))
        drawCircle(Color(0xFF7C5CFC), size.minDimension * .55f, Offset(size.width * .86f, size.height * .38f))
        drawCircle(Color(0xFF20C997), size.minDimension * .42f, Offset(size.width * .42f, size.height * .82f))
        repeat(9) { i -> drawLine(Color.White.copy(.23f), Offset(0f, size.height * i / 8f), Offset(size.width, size.height * (8-i) / 8f), 2f) }
    }
}

@Composable
private fun BoxScope.ControlSheet(backdrop: LayerBackdrop, blur: Float, setBlur: (Float)->Unit, refract: Float, setRefract: (Float)->Unit, edge: Float, setEdge: (Float)->Unit, chromatic: Boolean, setChromatic: (Boolean)->Unit) {
    Column(
        Modifier.align(Alignment.BottomCenter).padding(14.dp).fillMaxWidth()
            .drawBackdrop(backdrop, shape = { RoundedRectangle(30.dp) }, effects = { vibrancy(); blur(12.dp.toPx()); lens(12.dp.toPx(), 18.dp.toPx()) }, highlight = { Highlight.Plain }, onDrawSurface = { drawRect(Color(0xCC171820)) })
            .padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Param("模糊", blur, 0f..24f, setBlur)
        Param("折射", refract, 0f..0.7f, setRefract)
        Param("边缘厚度", edge, .02f..0.45f, setEdge)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("色散边缘", Modifier.weight(1f), color = Color.White); Switch(chromatic, setChromatic) }
    }
}

@Composable private fun Param(name: String, value: Float, range: ClosedFloatingPointRange<Float>, update: (Float)->Unit, onBegin:()->Unit = {}) {
    var changing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.width(78.dp), color = Color.White, fontSize = 13.sp)
        Slider(value, { if(!changing){changing=true;onBegin()};update(it) }, Modifier.weight(1f), valueRange = range, onValueChangeFinished={changing=false})
        Text("%.2f".format(value), Modifier.width(42.dp), color = Color.White.copy(.7f), fontSize = 11.sp)
    }
}

@Composable
private fun DockedWorkspace(
    modifier: Modifier = Modifier,
    toolsOpen: Boolean,
    aspect: Float,
    detailOpen: Boolean = false,
    canvas: @Composable BoxScope.() -> Unit,
    inspector: @Composable () -> Unit
) {
    BoxWithConstraints(modifier) {
        val wide=maxWidth>=700.dp
        if(toolsOpen&&detailOpen&&wide){
            Row(Modifier.fillMaxSize()){ArtworkViewport(Modifier.weight(1f),aspect,canvas);InspectorSurface(Modifier.width(340.dp).fillMaxHeight(),inspector)}
        }else if(toolsOpen&&detailOpen){
            Column(Modifier.fillMaxSize()){ArtworkViewport(Modifier.weight(1f),aspect,canvas);InspectorSurface(Modifier.fillMaxWidth().height(210.dp),inspector)}
        }else{
            ArtworkViewport(Modifier.fillMaxSize(), aspect, canvas)
            if(toolsOpen) Surface(Modifier.align(Alignment.BottomCenter).padding(horizontal=12.dp,vertical=10.dp).heightIn(min=56.dp,max=72.dp),shape=RoundedCornerShape(22.dp),color=Color(0xEE181820),tonalElevation=10.dp,shadowElevation=10.dp){
                Box(Modifier.padding(horizontal=10.dp,vertical=6.dp)){inspector()}
            }
        }
    }
}

@Composable
private fun ArtworkViewport(modifier: Modifier, aspect: Float, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier.background(Color(0xFF101016)).padding(12.dp), contentAlignment = Alignment.Center) {
        val safeAspect = aspect.coerceIn(.15f, 6f)
        val availableAspect = if (maxHeight.value > 0f) maxWidth / maxHeight else safeAspect
        val width = if (safeAspect >= availableAspect) maxWidth else maxHeight * safeAspect
        val height = if (safeAspect >= availableAspect) maxWidth / safeAspect else maxHeight
        Box(Modifier.size(width, height), content = content)
    }
}
private data class ArtworkTransform(val pxPerUnit:Float,val dpPerUnit:Float,val spPerUnit:Float)
private val LocalArtworkTransform=staticCompositionLocalOf{ArtworkTransform(1f,1f,1f)}

@Composable
private fun InspectorSurface(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(modifier, color = Color(0xFF181820), tonalElevation = 8.dp) {
        Box(Modifier.navigationBarsPadding().padding(14.dp)) { content() }
    }
}

@Composable
private fun BoxScope.WatermarkObject(
    texts:List<FreeText>,selectedTextId:Long?,plateWidth:Float,plateHeight:Float,rotation:Float,radius:Float,blur:Float,refract:Float,opacity:Float,
    offset:Offset,locked:Boolean,editing:Boolean,backdrop:LayerBackdrop,onGlassSelect:()->Unit,onTextSelect:(Long)->Unit,onTextChange:(Int,FreeText)->Unit,onDragStart:()->Unit,onDrag:(Offset)->Unit,onDragEnd:()->Unit
){
    val transform=LocalArtworkTransform.current
    Box(Modifier.align(Alignment.Center).offset{androidx.compose.ui.unit.IntOffset((offset.x*transform.pxPerUnit).toInt(),(offset.y*transform.pxPerUnit).toInt())}.fillMaxWidth(plateWidth).height((plateHeight*transform.dpPerUnit).dp).rotate(rotation)
        .pointerInput(editing,locked,transform.pxPerUnit){if(editing&&!locked)detectDragGestures(onDragStart={onGlassSelect();onDragStart()},onDragEnd=onDragEnd,onDragCancel=onDragEnd){change,drag->change.consume();onDrag(drag/transform.pxPerUnit)}}
        .pointerInput(editing){if(editing)detectTapGestures(onLongPress={onGlassSelect()})}){
    Box(
        Modifier.fillMaxSize()
            .drawBackdrop(backdrop,shape={RoundedRectangle((radius*transform.dpPerUnit).dp)},effects={vibrancy();blur((blur*transform.dpPerUnit).dp.toPx());lens((12*transform.dpPerUnit).dp.toPx(),(refract*transform.dpPerUnit).dp.toPx(),chromaticAberration=true)},highlight={Highlight.Plain},onDrawSurface={drawRect(Color.White.copy(opacity))})
            .clip(RoundedCornerShape((radius*transform.dpPerUnit).dp))
    ){texts.forEachIndexed{i,item->FreeTextObject(item,selectedTextId==item.id,editing,{onTextSelect(item.id)}){onTextChange(i,it)}}}
    if(locked)Surface(Modifier.align(Alignment.TopEnd).padding(6.dp),shape=RoundedCornerShape(12.dp),color=Color(0xAA111117)){Row(Modifier.padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Rounded.Lock,null,Modifier.size(13.dp));Text(" 已锁定",fontSize=10.sp)}}
    }
}

@Composable
private fun BoxScope.FreeTextObject(item:FreeText,selected:Boolean,editing:Boolean,onSelect:()->Unit,onChange:(FreeText)->Unit){
    val transform=LocalArtworkTransform.current
    val gestureItem=remember(item.id){mutableStateOf(item)}
    SideEffect{gestureItem.value=item}
    Box(
        Modifier.align(Alignment.Center).offset{androidx.compose.ui.unit.IntOffset((item.x*transform.pxPerUnit).toInt(),(item.y*transform.pxPerUnit).toInt())}
            .graphicsLayer(scaleX=item.scale,scaleY=item.scale,rotationZ=item.rotation)
            .then(if(selected&&editing)Modifier.border(1.dp,Color(0xFF82B1FF),RoundedCornerShape(5.dp)).padding(5.dp)else Modifier)
            .pointerInput(item.id,editing,selected,transform.pxPerUnit){
                if(editing&&selected)detectTransformGestures{_,pan,zoom,rotate->
                    val current=gestureItem.value
                    val changed=current.copy(x=current.x+pan.x/transform.pxPerUnit,y=current.y+pan.y/transform.pxPerUnit,scale=(current.scale*zoom).coerceIn(.25f,6f),rotation=current.rotation+rotate)
                    gestureItem.value=changed
                    onChange(changed)
                }
            }.pointerInput(item.id,editing,selected){if(editing&&!selected)detectTapGestures(onLongPress={onSelect()})}
    ){Text(item.text,color=Color.White,fontSize=(item.size*transform.spPerUnit).sp,fontWeight=FontWeight.SemiBold,style=androidx.compose.ui.text.TextStyle(shadow=androidx.compose.ui.graphics.Shadow(Color.Black.copy(.55f),Offset(1f,1f),3f)))}
}

@Composable
private fun TemplateDialog(type:String, currentJson:()->String, onLoad:(String)->Unit, onDismiss:()->Unit) {
    val context=LocalContext.current;var name by remember{mutableStateOf("我的模板")};var templates by remember{mutableStateOf(loadTemplates(context,type))}
    AlertDialog(onDismissRequest=onDismiss,title={Text("模板库")},text={
        Column(Modifier.heightIn(max=420.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            LabTextField("模板名称",name,{name=it})
            Button({if(name.isNotBlank()){saveTemplate(context,type,name.trim(),currentJson());templates=loadTemplates(context,type)}} ,Modifier.fillMaxWidth()){Icon(Icons.Rounded.Save,null);Text(" 保存当前配置")}
            HorizontalDivider()
            if(templates.isEmpty())Text("还没有模板。模板只保存布局和材质，不复制照片。",color=Color.Gray)
            Column(Modifier.verticalScroll(rememberScrollState())){templates.forEach{(templateName,json)->
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    TextButton({onLoad(json);onDismiss()},Modifier.weight(1f)){Text(templateName,Modifier.fillMaxWidth(),textAlign=TextAlign.Start)}
                    IconButton({templatePrefs(context).edit().remove("$type:$templateName").apply();templates=loadTemplates(context,type)}){Icon(Icons.Rounded.Delete,"删除模板")}
                }
            }}
        }
    },confirmButton={TextButton(onDismiss){Text("完成")}})
}

@Composable
private fun WatermarkLab(modifier: Modifier = Modifier) {
    var image by remember { mutableStateOf<LoadedImage?>(null) }
    var title by remember { mutableStateOf("SHOT ON LIQUID") }
    var subtitle by remember { mutableStateOf("35mm · f/1.8 · 1/250s") }
    var titleSize by remember { mutableFloatStateOf(18f) }
    var blur by remember { mutableFloatStateOf(5f) }
    var refract by remember { mutableFloatStateOf(24f) }
    var radius by remember { mutableFloatStateOf(24f) }
    var opacity by remember { mutableFloatStateOf(.18f) }
    var plateOffset by remember { mutableStateOf(Offset.Zero) }
    var plateWidth by remember { mutableFloatStateOf(.92f) }
    var plateHeight by remember { mutableFloatStateOf(112f) }
    var padding by remember { mutableFloatStateOf(18f) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var centerText by remember { mutableStateOf(false) }
    var plateLocked by remember { mutableStateOf(false) }
    val clones=remember{mutableStateListOf<Offset>()};var canvasSize by remember{mutableStateOf(IntSize.Zero)}
    val freeTexts=remember{mutableStateListOf(FreeText(-1L,"SHOT ON LIQUID",-42f,-16f,18f,0f),FreeText(-2L,"35mm · f/1.8 · 1/250s",-42f,15f,12f,0f))};var selectedTextId:Long? by remember{mutableStateOf(null)}
    var controlsOpen by remember { mutableStateOf(false) }
    var editorDialog:Int? by remember { mutableStateOf(null) }
    var exporting by remember { mutableStateOf(false) }
    var draftLoaded by remember{mutableStateOf(false)};var lastSaved by remember{mutableStateOf<String?>(null)}
    val undoStack=remember{mutableStateListOf<WatermarkSnapshot>()};val redoStack=remember{mutableStateListOf<WatermarkSnapshot>()};var showTemplates by remember{mutableStateOf(false)}
    val snapshot={WatermarkSnapshot(title,subtitle,titleSize,blur,refract,radius,opacity,plateOffset.x,plateOffset.y,plateWidth,plateHeight,padding,rotation,centerText,plateLocked,clones.toList(),freeTexts.toList())}
    val applySnapshot: (WatermarkSnapshot)->Unit = {s->title=s.title;subtitle=s.subtitle;titleSize=s.titleSize;blur=s.blur;refract=s.refract;radius=s.radius;opacity=s.opacity;plateOffset=Offset(s.offsetX,s.offsetY);plateWidth=s.width;plateHeight=s.height;padding=s.padding;rotation=s.rotation;centerText=s.centered;plateLocked=s.locked;clones.clear();clones.addAll(s.clones);freeTexts.clear();freeTexts.addAll(s.texts);selectedTextId=null}
    val checkpoint={val s=snapshot();if(undoStack.lastOrNull()!=s){undoStack.add(s);if(undoStack.size>60)undoStack.removeAt(0)};redoStack.clear()}
    val undo={if(undoStack.isNotEmpty()){redoStack.add(snapshot());applySnapshot(undoStack.removeAt(undoStack.lastIndex))}}
    val redo={if(redoStack.isNotEmpty()){undoStack.add(snapshot());applySnapshot(redoStack.removeAt(redoStack.lastIndex))}}
    var dragStartSnapshot:WatermarkSnapshot? by remember{mutableStateOf(null)}
    val beginDrag={dragStartSnapshot=snapshot()}
    val endDrag={dragStartSnapshot?.let{start->if(start!=snapshot()){undoStack.add(start);if(undoStack.size>60)undoStack.removeAt(0);redoStack.clear()}};dragStartSnapshot=null}
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scope = rememberCoroutineScope()
    val artworkLayer = rememberGraphicsLayer()
    val targetWidth = image?.width ?: 1080
    val targetHeight = image?.height ?: 1350
    var pendingExport by remember { mutableStateOf(false) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed && pendingExport) scope.launch { exporting=true; exportLayer(context,artworkLayer,targetWidth,targetHeight,"Artwork"); exporting=false }; pendingExport=false
    }
    val export: () -> Unit = {
        if(exporting) Unit else if(Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context,android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingExport=true; storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else scope.launch { exporting=true;controlsOpen=false;delay(180);exportLayer(context,artworkLayer,targetWidth,targetHeight,"Artwork").onSuccess{lastSaved=it};exporting=false }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        loadImage(context,uri)?.let { image = it; plateOffset=Offset.Zero }
    }
    val backdrop = rememberLayerBackdrop()
    LaunchedEffect(Unit){templatePrefs(context).getString("draft:watermark",null)?.let{runCatching{watermarkFromJson(it)}.getOrNull()?.let(applySnapshot)};draftLoaded=true}
    LaunchedEffect(draftLoaded,title,subtitle,titleSize,blur,refract,radius,opacity,plateOffset,plateWidth,plateHeight,padding,rotation,centerText,plateLocked,clones.toList(),freeTexts.toList()){if(draftLoaded){delay(350);templatePrefs(context).edit().putString("draft:watermark",snapshot().toJson()).apply()}}
    Column(modifier.fillMaxSize().background(Color(0xFF101016))) {
        Column(Modifier.statusBarsPadding().padding(horizontal=10.dp,vertical=4.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text("玻璃铭牌",color=Color.White,fontWeight=FontWeight.Bold);Text("${1+clones.size} 个铭牌",color=Color.Gray,fontSize=11.sp)}
                IconButton(undo,enabled=undoStack.isNotEmpty()){Icon(Icons.Rounded.Undo,"撤销")};IconButton(redo,enabled=redoStack.isNotEmpty()){Icon(Icons.Rounded.Redo,"重做")}
                FilledTonalButton({controlsOpen=!controlsOpen;selectedTextId=null;editorDialog=null}){Icon(if(controlsOpen)Icons.Rounded.Visibility else Icons.Rounded.Edit,null);Text(if(controlsOpen)"完成" else "编辑")}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},Modifier.weight(1f)){Icon(Icons.Rounded.PhotoLibrary,null);Text("选图")};OutlinedButton({showTemplates=true},Modifier.weight(1f)){Icon(Icons.Rounded.Bookmarks,null);Text("模板")};FilledTonalButton(export,Modifier.weight(1f),enabled=!exporting){if(exporting)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else Icon(Icons.Rounded.SaveAlt,null);Text(if(exporting)"保存中" else "保存")}}
        }
        if(lastSaved!=null)Text("最近保存：$lastSaved",Modifier.padding(horizontal=12.dp),color=Color(0xFF8FD3A8),fontSize=11.sp,maxLines=1)
        DockedWorkspace(Modifier.weight(1f), controlsOpen, image?.aspect ?: .8f, detailOpen=editorDialog!=null, canvas = {
            Box(Modifier.fillMaxSize().onSizeChanged{canvasSize=it}.drawWithContent { artworkLayer.record { this@drawWithContent.drawContent() }; drawLayer(artworkLayer) }) {
            val pxPerUnit=(canvasSize.width.toFloat()/360f).coerceAtLeast(.1f)
            val artworkTransform=ArtworkTransform(pxPerUnit,pxPerUnit/density.density,pxPerUnit/(density.density*density.fontScale))
            CompositionLocalProvider(LocalArtworkTransform provides artworkTransform){
            if (image != null) Image(BitmapPainter(image!!.bitmap), null, Modifier.fillMaxSize().layerBackdrop(backdrop), contentScale = ContentScale.Fit) else DemoBackdrop(Modifier.fillMaxSize().layerBackdrop(backdrop))
            WatermarkObject(freeTexts,selectedTextId,plateWidth,plateHeight,rotation,radius,blur,refract,opacity,plateOffset,plateLocked,controlsOpen,backdrop,{selectedTextId=null},{selectedTextId=it},{i,changed->if(freeTexts[i]!=changed){if(dragStartSnapshot==null)dragStartSnapshot=snapshot();freeTexts[i]=changed}},beginDrag,{d->plateOffset+=d},endDrag)
            clones.forEachIndexed{i,o->WatermarkObject(freeTexts,null,plateWidth,plateHeight,rotation,radius,blur,refract,opacity,o,false,false,backdrop,{},{},{_,_->},beginDrag,{d->clones[i]=clones[i]+d},endDrag)}
            }
            }
        }, inspector = {
            val selectedIndex=freeTexts.indexOfFirst{it.id==selectedTextId};val selected=freeTexts.getOrNull(selectedIndex)
            if(editorDialog==null)Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
                AssistChip({checkpoint();val stagger=(freeTexts.size%6)*14f;val item=FreeText(System.nanoTime(),"新文本",-36f+stagger,-8f+stagger,20f,0f);freeTexts.add(item);selectedTextId=item.id;editorDialog=0},{Icon(Icons.Rounded.TextFields,null);Text("添加文字")})
                AssistChip({editorDialog=0},{Text(if(selectedTextId!=null)"编辑文字" else "文字")});AssistChip({selectedTextId=null;editorDialog=1},{Text("玻璃")});AssistChip({editorDialog=2},{Text("材质")})
                if(selectedTextId!=null)AssistChip({if(selectedIndex>=0){checkpoint();freeTexts.removeAt(selectedIndex);selectedTextId=null}},{Icon(Icons.Rounded.Delete,null);Text("删除")})
                AssistChip({controlsOpen=false;selectedTextId=null;editorDialog=null},{Icon(Icons.Rounded.Check,null);Text("完成")})
            }else Column(Modifier.fillMaxSize()){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(when(editorDialog){0->"玻璃文字";1->"玻璃尺寸";else->"玻璃材质"},Modifier.weight(1f),fontWeight=FontWeight.Bold);TextButton({editorDialog=null}){Icon(Icons.Rounded.ExpandMore,null);Text("收起")}}
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())){when(editorDialog){
                    0->{if(selected!=null){LabTextField("内容",selected.text,{v->if(freeTexts[selectedIndex].text!=v){checkpoint();freeTexts[selectedIndex]=freeTexts[selectedIndex].copy(text=v)}});Param("字号",selected.size,8f..96f,{freeTexts[selectedIndex]=freeTexts[selectedIndex].copy(size=it)},checkpoint);Param("缩放",selected.scale,.25f..6f,{freeTexts[selectedIndex]=freeTexts[selectedIndex].copy(scale=it)},checkpoint);Param("旋转",selected.rotation,-180f..180f,{freeTexts[selectedIndex]=freeTexts[selectedIndex].copy(rotation=it)},checkpoint)}else Text("先长按铭牌内的一段文字；选中后可直接拖动和双指缩放旋转。") }
                    1->{Param("宽度",plateWidth,.25f..1f,{plateWidth=it},checkpoint);Param("高度",plateHeight,48f..300f,{plateHeight=it},checkpoint);Param("旋转",rotation,-45f..45f,{rotation=it},checkpoint);TextButton({checkpoint();plateOffset=Offset.Zero;rotation=0f}){Text("居中并复位角度")}}
                    else->{Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){AssistChip({checkpoint();blur=1f;refract=14f;opacity=.1f},{Text("清透")});AssistChip({checkpoint();blur=14f;refract=18f;opacity=.24f},{Text("柔雾")});AssistChip({checkpoint();blur=3f;refract=52f;opacity=.12f},{Text("棱镜")})};Param("模糊",blur,0f..24f,{blur=it},checkpoint);Param("折射",refract,0f..64f,{refract=it},checkpoint);Param("圆角",radius,0f..80f,{radius=it},checkpoint);Param("明度",opacity,0f..0.6f,{opacity=it},checkpoint)}
                }}
            }
        })
    }
    if(showTemplates)TemplateDialog("watermark",{snapshot().toJson()},{json->runCatching{watermarkFromJson(json)}.getOrNull()?.let{checkpoint();applySnapshot(it)}},{showTemplates=false})
}

@Composable private fun LabTextField(label: String, value: String, change: (String)->Unit, onBegin:()->Unit = {}) {
    var focused by remember { mutableStateOf(false) }
    Column { Text(label, color = Color.Gray, fontSize = 12.sp); BasicTextField(value, change, Modifier.fillMaxWidth().onFocusChanged { if(it.isFocused&&!focused)onBegin();focused=it.isFocused }.background(Color(0xFF24242D), RoundedCornerShape(12.dp)).padding(13.dp), textStyle = androidx.compose.ui.text.TextStyle(color = Color.White)) }
}

@Composable private fun CameraLab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var explainPermission by remember { mutableStateOf(!granted) }
    var glassOffset by remember { mutableStateOf(Offset.Zero) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var torch by remember { mutableStateOf(false) }
    val backdrop = rememberLayerBackdrop()
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(granted) {
        if(granted) {
            val future=ProcessCameraProvider.getInstance(context)
            future.addListener({ runCatching {
                val provider=future.get(); val analysis=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                analysis.setAnalyzer(executor) { proxy ->
                    runCatching { rgbaBitmap(proxy) }.getOrNull()?.let { bmp -> ContextCompat.getMainExecutor(context).execute { lastBitmap=bmp; frame=bmp.asImageBitmap() } }; proxy.close()
                }; provider.unbindAll(); camera=provider.bindToLifecycle(owner,CameraSelector.DEFAULT_BACK_CAMERA,analysis)
            } },ContextCompat.getMainExecutor(context))
        }
        onDispose { executor.shutdownNow() }
    }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (granted && frame!=null) Image(frame!!,null,Modifier.fillMaxSize().layerBackdrop(backdrop),contentScale=ContentScale.Crop) else if(!granted) Button({ request.launch(android.Manifest.permission.CAMERA) }, Modifier.align(Alignment.Center)) { Text("允许相机权限") } else CircularProgressIndicator(Modifier.align(Alignment.Center))

        Box(Modifier.align(Alignment.Center).offset { androidx.compose.ui.unit.IntOffset(glassOffset.x.toInt(), glassOffset.y.toInt()) }.size(210.dp, 90.dp)
            .drawBackdrop(backdrop,shape={RoundedRectangle(45.dp)},effects={vibrancy();blur(3.dp.toPx());lens(18.dp.toPx(),36.dp.toPx(),chromaticAberration=true)},highlight={Highlight.Plain},onDrawSurface={drawRect(Color.White.copy(.16f))})) {
            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Icon(if(torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "闪光灯", Modifier.clickable { torch=!torch; camera?.cameraControl?.enableTorch(torch) }, tint = Color.White)
                Text(String.format(Locale.US,"%.1f×",zoom),Modifier.clickable { zoom=if(zoom<1.5f) 2f else 1f; camera?.cameraControl?.setZoomRatio(zoom) },color=Color.White,fontWeight=FontWeight.Bold)
                Icon(Icons.Rounded.Exposure, "曝光", tint = Color.White)
            }
            Box(Modifier.align(Alignment.BottomCenter).width(70.dp).height(18.dp).pointerInput(Unit){detectDragGestures{c,d->c.consume();glassOffset+=d}}){Box(Modifier.align(Alignment.Center).size(34.dp,3.dp).background(Color.White.copy(.65f),RoundedCornerShape(2.dp)))}
        }
        Text("实时可折射取景 · 点击闪光/变焦", Modifier.statusBarsPadding().padding(20.dp), color = Color.White, fontWeight = FontWeight.Bold)
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(28.dp).size(76.dp).drawBackdrop(backdrop,shape={RoundedRectangle(38.dp)},effects={blur(3.dp.toPx());lens(10.dp.toPx(),18.dp.toPx())},highlight={Highlight.Plain},onDrawSurface={drawRect(Color.White.copy(.22f))}).padding(7.dp).background(Color.White,RoundedCornerShape(32.dp)).clickable { lastBitmap?.let { saveBitmap(context,it,"Camera") } })
    }
    if(explainPermission && !granted) AlertDialog(
        onDismissRequest={explainPermission=false},
        icon={Icon(Icons.Rounded.CameraAlt,null)}, title={Text("需要相机权限")},
        text={Text("用于实时显示取景画面、验证柔光玻璃折射并拍摄照片。画面只在本机处理，不会上传。")},
        confirmButton={TextButton({explainPermission=false;request.launch(android.Manifest.permission.CAMERA)}){Text("继续授权")}},
        dismissButton={TextButton({explainPermission=false}){Text("暂不使用")}}
    )
}

private fun rgbaBitmap(proxy: androidx.camera.core.ImageProxy): Bitmap {
    val plane=proxy.planes[0]; val buffer=plane.buffer; buffer.rewind(); val w=proxy.width; val h=proxy.height
    val raw=Bitmap.createBitmap(plane.rowStride/plane.pixelStride,h,Bitmap.Config.ARGB_8888); raw.copyPixelsFromBuffer(buffer)
    val cropped=Bitmap.createBitmap(raw,0,0,w,h); val rotation=proxy.imageInfo.rotationDegrees.toFloat()
    if(rotation==0f) return cropped
    return Bitmap.createBitmap(cropped,0,0,w,h,Matrix().apply{postRotate(rotation)},true)
}

@Composable private fun BoxScope.CardTextObject(name:String,detail:String,size:Float,offset:Offset,editing:Boolean,selected:Boolean,onSelect:()->Unit,onBegin:()->Unit,onMove:(Offset)->Unit){
    Column(Modifier.align(Alignment.Center).offset{androidx.compose.ui.unit.IntOffset(offset.x.toInt(),offset.y.toInt())}.width(190.dp)
        .then(if(editing&&selected)Modifier.border(1.dp,Color(0xFF82B1FF),RoundedCornerShape(6.dp)).padding(5.dp)else Modifier)
        .pointerInput(editing,selected){if(editing&&selected)detectDragGestures(onDragStart={onBegin()}){c,d->c.consume();onMove(d)}}
        .pointerInput(editing,selected){if(editing&&!selected)detectTapGestures(onLongPress={onSelect()})}){
        Text(name,color=Color.White,fontSize=size.sp,fontWeight=FontWeight.Black);Text(detail,color=Color.White.copy(.8f),fontSize=(size*.58f).sp);Spacer(Modifier.height(12.dp));Text("SCAN TO CONNECT",color=Color.White.copy(.55f),fontSize=9.sp)
    }
}
@Composable private fun BoxScope.CardQrObject(qr:ImageBitmap,size:Float,offset:Offset,editing:Boolean,selected:Boolean,backdrop:LayerBackdrop,onSelect:()->Unit,onBegin:()->Unit,onMove:(Offset)->Unit){
    Box(Modifier.align(Alignment.Center).offset{androidx.compose.ui.unit.IntOffset(offset.x.toInt(),offset.y.toInt())}.size(size.dp)
        .drawBackdrop(backdrop,shape={RoundedRectangle(20.dp)},effects={vibrancy();blur(3.dp.toPx());lens(8.dp.toPx(),12.dp.toPx())},highlight={Highlight.Plain},onDrawSurface={drawRect(Color.White.copy(.72f))})
        .then(if(editing&&selected)Modifier.border(2.dp,Color(0xFF82B1FF),RoundedCornerShape(20.dp))else Modifier).padding(9.dp)
        .pointerInput(editing,selected){if(editing&&selected)detectDragGestures(onDragStart={onBegin()}){c,d->c.consume();onMove(d)}}
        .pointerInput(editing,selected){if(editing&&!selected)detectTapGestures(onLongPress={onSelect()})}){Image(BitmapPainter(qr),"二维码",Modifier.fillMaxSize())}
}

@Composable private fun CardLab(modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf("LIQUID MAKER") }; var detail by rememberSaveable { mutableStateOf("hello@example.com") }
    var payload by rememberSaveable { mutableStateOf("https://example.com") }; var background by remember { mutableStateOf<LoadedImage?>(null) }
    var textOffset by remember { mutableStateOf(Offset(-70f,0f)) }; var qrOffset by remember { mutableStateOf(Offset(110f,0f)) }
    var textSize by remember { mutableFloatStateOf(20f) }; var qrSize by remember { mutableFloatStateOf(118f) }
    var cardRadius by remember { mutableFloatStateOf(28f) }; var blur by remember { mutableFloatStateOf(5f) }; var refract by remember { mutableFloatStateOf(28f) }; var opacity by remember { mutableFloatStateOf(.13f) }
    var textLocked by remember{mutableStateOf(false)};var qrLocked by remember{mutableStateOf(false)};var qrOnTop by remember{mutableStateOf(true)}
    var tools by remember { mutableStateOf(false) }; var selectedCardObject:Int? by remember{mutableStateOf(null)};var cardDialog:Int? by remember{mutableStateOf(null)}
    var draftLoaded by remember{mutableStateOf(false)};var cardSaving by remember{mutableStateOf(false)};var lastSaved by remember{mutableStateOf<String?>(null)}
    val undoStack=remember{mutableStateListOf<CardSnapshot>()};val redoStack=remember{mutableStateListOf<CardSnapshot>()};var showTemplates by remember{mutableStateOf(false)}
    val snapshot={CardSnapshot(name,detail,payload,textOffset.x,textOffset.y,qrOffset.x,qrOffset.y,textSize,qrSize,cardRadius,blur,refract,opacity,textLocked,qrLocked,qrOnTop)}
    val applySnapshot: (CardSnapshot)->Unit = {s->name=s.name;detail=s.detail;payload=s.payload;textOffset=Offset(s.textX,s.textY);qrOffset=Offset(s.qrX,s.qrY);textSize=s.textSize;qrSize=s.qrSize;cardRadius=s.radius;blur=s.blur;refract=s.refract;opacity=s.opacity;textLocked=s.textLocked;qrLocked=s.qrLocked;qrOnTop=s.qrOnTop}
    val checkpoint={val s=snapshot();if(undoStack.lastOrNull()!=s){undoStack.add(s);if(undoStack.size>60)undoStack.removeAt(0)};redoStack.clear()}
    val undo={if(undoStack.isNotEmpty()){redoStack.add(snapshot());applySnapshot(undoStack.removeAt(undoStack.lastIndex))}};val redo={if(redoStack.isNotEmpty()){undoStack.add(snapshot());applySnapshot(redoStack.removeAt(redoStack.lastIndex))}}
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val backdrop = rememberLayerBackdrop(); val cardLayer=rememberGraphicsLayer()
    val density=androidx.compose.ui.platform.LocalDensity.current
    val backgroundPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->uri?.let{loadImage(context,it)}?.let{background=it}}
    val qr = remember(payload) { qrBitmap(payload) }
    LaunchedEffect(Unit){templatePrefs(context).getString("draft:card",null)?.let{runCatching{cardFromJson(it)}.getOrNull()?.let(applySnapshot)};draftLoaded=true}
    LaunchedEffect(draftLoaded,name,detail,payload,textOffset,qrOffset,textSize,qrSize,cardRadius,blur,refract,opacity,textLocked,qrLocked,qrOnTop){if(draftLoaded){delay(350);templatePrefs(context).edit().putString("draft:card",snapshot().toJson()).apply()}}
    Column(modifier.fillMaxSize().background(Color(0xFF101016))) {
        Column(Modifier.statusBarsPadding().padding(horizontal=10.dp,vertical=4.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("玻璃数字名片",Modifier.weight(1f),color=Color.White,fontWeight=FontWeight.Bold);IconButton(undo,enabled=undoStack.isNotEmpty()){Icon(Icons.Rounded.Undo,"撤销")};IconButton(redo,enabled=redoStack.isNotEmpty()){Icon(Icons.Rounded.Redo,"重做")};FilledTonalButton({tools=!tools;selectedCardObject=null;cardDialog=null}){Icon(if(tools)Icons.Rounded.Check else Icons.Rounded.Edit,null);Text(if(tools)"完成" else "编辑")}}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({backgroundPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},Modifier.weight(1f)){Icon(Icons.Rounded.PhotoLibrary,null);Text("背景")};OutlinedButton({showTemplates=true},Modifier.weight(1f)){Icon(Icons.Rounded.Bookmarks,null);Text("模板")};FilledTonalButton({if(!cardSaving)scope.launch{cardSaving=true;tools=false;selectedCardObject=null;cardDialog=null;delay(160);exportLayer(context,cardLayer,1580,1000,"Card",with(density){cardRadius.dp.toPx()}).onSuccess{lastSaved=it};cardSaving=false}},Modifier.weight(1f),enabled=!cardSaving){if(cardSaving)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else Icon(Icons.Rounded.SaveAlt,null);Text(if(cardSaving)"保存中" else "保存")}}
        }
        if(lastSaved!=null)Text("最近保存：$lastSaved",Modifier.padding(horizontal=12.dp),color=Color(0xFF8FD3A8),fontSize=11.sp,maxLines=1)
        DockedWorkspace(Modifier.weight(1f),tools,1.58f,detailOpen=cardDialog!=null,canvas={
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(cardRadius.dp)).drawWithContent{cardLayer.record{this@drawWithContent.drawContent()};drawLayer(cardLayer)}) {
            if(background!=null) Image(BitmapPainter(background!!.bitmap),null,Modifier.fillMaxSize().layerBackdrop(backdrop),contentScale=ContentScale.Crop) else DemoBackdrop(Modifier.fillMaxSize().layerBackdrop(backdrop))
            Box(Modifier.fillMaxSize().drawBackdrop(backdrop,shape={RoundedRectangle(cardRadius.dp)},effects={vibrancy();blur(blur.dp.toPx());lens(14.dp.toPx(),refract.dp.toPx(),chromaticAberration=true)},highlight={Highlight.Plain},onDrawSurface={drawRect(Color.White.copy(opacity))}))
            if(qrOnTop){CardTextObject(name,detail,textSize,textOffset,tools,selectedCardObject==0,{selectedCardObject=0},checkpoint){textOffset+=it};CardQrObject(qr,qrSize,qrOffset,tools,selectedCardObject==1,backdrop,{selectedCardObject=1},checkpoint){qrOffset+=it}}
            else{CardQrObject(qr,qrSize,qrOffset,tools,selectedCardObject==1,backdrop,{selectedCardObject=1},checkpoint){qrOffset+=it};CardTextObject(name,detail,textSize,textOffset,tools,selectedCardObject==0,{selectedCardObject=0},checkpoint){textOffset+=it}}
        }
        },inspector={if(cardDialog==null)Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
            AssistChip({selectedCardObject=0;cardDialog=0},{Icon(Icons.Rounded.TextFields,null);Text("文字")});AssistChip({selectedCardObject=1;cardDialog=1},{Icon(Icons.Rounded.QrCode,null);Text("二维码")});AssistChip({cardDialog=2},{Text("材质")});AssistChip({checkpoint();textOffset=Offset(-70f,0f);qrOffset=Offset(110f,0f)},{Text("复位")});AssistChip({tools=false;selectedCardObject=null;cardDialog=null},{Icon(Icons.Rounded.Check,null);Text("完成")})
        }else Column(Modifier.fillMaxSize()){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(when(cardDialog){0->"名片文字";1->"二维码";else->"玻璃材质"},Modifier.weight(1f),fontWeight=FontWeight.Bold);TextButton({cardDialog=null}){Icon(Icons.Rounded.ExpandMore,null);Text("收起")}}
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())){when(cardDialog){
                0->{LabTextField("名字 / 品牌",name,{name=it},checkpoint);LabTextField("联系方式",detail,{detail=it},checkpoint);Param("文字大小",textSize,10f..36f,{textSize=it},checkpoint)}
                1->{LabTextField("二维码内容",payload,{payload=it},checkpoint);Param("二维码大小",qrSize,72f..190f,{qrSize=it},checkpoint);Row(verticalAlignment=Alignment.CenterVertically){Text("二维码置顶",Modifier.weight(1f));Switch(qrOnTop,{checkpoint();qrOnTop=it})}}
                else->{Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){AssistChip({checkpoint();blur=1f;refract=14f;opacity=.1f},{Text("清透")});AssistChip({checkpoint();blur=14f;refract=18f;opacity=.24f},{Text("柔雾")});AssistChip({checkpoint();blur=3f;refract=52f;opacity=.12f},{Text("棱镜")})};Param("圆角",cardRadius,0f..64f,{cardRadius=it},checkpoint);Param("模糊",blur,0f..24f,{blur=it},checkpoint);Param("折射",refract,0f..64f,{refract=it},checkpoint);Param("明度",opacity,0f..0.5f,{opacity=it},checkpoint)}
            }}
        }})
    }
    if(showTemplates)TemplateDialog("card",{snapshot().toJson()},{json->runCatching{cardFromJson(json)}.getOrNull()?.let{checkpoint();applySnapshot(it)}},{showTemplates=false})
}

private fun qrBitmap(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(text.ifBlank { " " }, BarcodeFormat.QR_CODE, 512, 512)
    val pixels = IntArray(512*512) { i -> if(matrix[i%512,i/512]) android.graphics.Color.rgb(12,18,30) else android.graphics.Color.TRANSPARENT }
    return Bitmap.createBitmap(pixels,512,512,Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable private fun IdeaCard(icon: ImageVector, title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF202029))) { Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) { Icon(icon, null, tint = Color(0xFF82B1FF)); Column { Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp); Spacer(Modifier.height(5.dp)); Text(body, color = Color(0xFFB7B7C7), lineHeight = 20.sp) } } }
}

private suspend fun exportLayer(context: android.content.Context, layer: androidx.compose.ui.graphics.layer.GraphicsLayer, width: Int, height: Int, prefix: String, cornerRadiusPx: Float = 0f): Result<String> {
    return runCatching {
        if(layer.size.width<=0||layer.size.height<=0)error("作品画布尚未完成渲染，请稍后重试")
        val renderedSource = layer.toImageBitmap().asAndroidBitmap()
        val rendered = renderedSource.copy(Bitmap.Config.ARGB_8888,false) ?: error("无法创建软件位图")
        val outputSize = fitWithin(width,height,4096)
        val scaled = Bitmap.createScaledBitmap(rendered,outputSize.first,outputSize.second,true)
        val scaledRadius = if(layer.size.height>0) cornerRadiusPx/layer.size.height*scaled.height else 0f
        val output = if(cornerRadiusPx>0f) roundedBitmap(scaled,scaledRadius) else scaled
        saveBitmap(context,output,prefix).getOrThrow()
    }.onFailure { Toast.makeText(context, "保存失败：${it.message}", Toast.LENGTH_LONG).show() }
}

private fun fitWithin(width:Int,height:Int,max:Int):Pair<Int,Int>{
    if(width<=max && height<=max)return width to height
    val scale=max.toFloat()/maxOf(width,height);return (width*scale).toInt() to (height*scale).toInt()
}

private fun roundedBitmap(source: Bitmap, radius: Float): Bitmap {
    val out=Bitmap.createBitmap(source.width,source.height,Bitmap.Config.ARGB_8888); val canvas=AndroidCanvas(out)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG); canvas.drawRoundRect(0f,0f,source.width.toFloat(),source.height.toFloat(),radius,radius,paint)
    paint.xfermode=android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN);canvas.drawBitmap(source,0f,0f,paint);return out
}

private fun saveBitmap(context: android.content.Context, bitmap: Bitmap, prefix: String):Result<String> {
    return runCatching {
        val name = "LiquidGlass_${prefix}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
        val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LiquidGlassLab") }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建媒体文件")
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        name
    }.onSuccess { Toast.makeText(context, "已保存：$it", Toast.LENGTH_SHORT).show() }
        .onFailure { Toast.makeText(context, "保存失败：${it.message}", Toast.LENGTH_LONG).show() }
}

@Composable private fun GlassLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF82B1FF), secondary = Color(0xFFFFB86B)), content = content)
}
