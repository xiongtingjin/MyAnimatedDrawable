# 从零开始撸一个Fresco之gif和Webp动画
> 转载请注明出处
> **Fresco源代码文档翻译项目请看这里：[FrescoFresco源代码翻译项目](https://github.com/whenSunSet/MyFresco/tree/master)** 这个项目会不断更新想学习Fresco源代码的同学一定不要错过。
> Fresco中有个很重要的功能就是gif和Webp动画的实现，今天我就来讲解一下这个模块，顺便撸了个模块demo出来。这是项目的github地址[Fresco动画模块](https://github.com/whenSunSet/MyAnimatedDrawable)，推荐看博客的时候结合项目一起看，项目中绝大部分类都有细致的注释，看起来还是很清晰的。

## 一、项目包结构
- 1.animated：
	- 1.gif：这个包中的两个类都使用了jni代码，GifImage有两个功能：1.用于将Gif动画已解码数据储存在jni代码管理的本地内存中2.通过jni代码解析未解码的Gif数据。GifFrame则是储存Gif动画单个帧的数据也是通过jni代码管里的本地内存。
	- 2.webp:WebPImage类似前面的GifImage，只不过换成了Webp的数据。WebPFrame同理类似GifFrame
- 2.bitmapFactory:无论是动画的帧还是静态的图片，最后都需要创建为Bitmap显示在View上。而不同Android版本创建Bitmap的方式是不同的，这里的工厂不同的工厂就是用于不同Android版本创建Bitmap这里的工厂用到了后面的platformDecoder包中的解码器，platformDecoder包中的类才是在不同Android版本下创建Bitmap的具体代码逻辑。
- 3.cacheKey:每一个Key对应一个图片或动画。
- 4.common:
	- 1.nativeLoader:每一个Key对应一个图片或动画。
	- 2.s:不同类的工具方法，如Ints中有int的工具方法。
	- 3.stream:简单的基于Java流的包装类
	- 4.util:工具类
- 5.excutor:
	- 1.executorSupplier:包的实现在DefaultExecutorSupplier中，用于提供不同的Executor
	- 2.handlerExcutor:用于提供转移到Handler线程的Excutor，唯一实现是UiThreadImmediateExecutorService，转到主线程。
	- 3.serailExcutor:用于提供串行执行任务的Excutor，DefaultSerialExecutorService是具体实现。
- 6.image:包对外的实现是CloseableAnimatedImage和CloseableStaticBitmap，一个用于封装动画数据，一个用于封装静态图片信息。
	- 1.imageDecode:AnimatedImageDecoder的实现为animated包下的GifImage和WebPImage，用于解码未解码的动画数据。ImageDecoder的实现是DefaultImageDecoder用于解码所有图像数据，其用到了AnimatedImageFactoryImpl以提供CloseableAnimatedImage。
	- 2.imageFormat:这个包用于检测EcodingImage类中的数据是什么类型的图像数据。
	- 3.imageInfo:用于储存图片的简单信息和图片目前的质量。
- 7.imagepipeline：
	- 1.memory：NativeMemoryChunk是本地内存块的java封装，用于提供一块本地内存，这里的本地内存使用jni代码管理。NativePooledByteBuffer则是基于NativeMemoryChunk提供了一个字节池，用来提供可回收使用的字节数组。
	- 2.nativecode：各种使用到了jni代码的java封装类
- 8.platformDecoder：bitmapFactory包中各个工厂生成Bitmap的具体实现包。
- 9.pool：这个包里是各种资源可回收使用的对象池子，如Bitmap和Byte数组等等。这样的好处是减少内存频繁GC带来的卡顿。
	- 1.poolFactory：这个包的对外实现是PoolFactory，用于提供各种Pool。
	- 2.poolParams：这个包中提供各种Pool的参数。
	- 3.poolUtilpool的工具包，BitmapCounter用于计数Bitmap，保持跟踪总的byte的数量，和Bitmap数量。PoolStatsTracker用来跟踪各种Pool的操作。
- 10.refrence：包对外提供的实现是CloseableReference，以引用计数的方式将一些可关闭的大数据块关闭。类似JVM的内存回收，当引用计数为0时，内存会自动释放。
- 11.webpsupport：在Android2.3以下是不支持Webp的，这个包中的类就是用来让2.3一下的Android机可以使用Webp。
- 12：factoryAndProvider：这个包就是动画的主要实现逻辑，	最终提供的是AnimatedDrawable类，这个类只要直接设置在Veiw上就能使View显示Gif或者Webp的动画。这个包我使用树状层次来描述各个类之间的使用关系，所以比较复杂，大家可以结合后面的图片一起观看。AnimatedFactoryProvider用于提供一个AnimatedFactory
	- 1.animatedFactory：AnimatedFactory用于返回创建一个Gif和Webp动画的两个重要工厂：AnimatedDrawableFactory和AnimatedImageFactory。AnimatedFactoryImpl是具体实现。
		- 1.animatedImageFactory：AnimatedImageFactory用于将EncodedImage这个未解码的Gif或者Webp的数据类，解码成CloseableImage,如果解码成功这里的CloseableImage的实现是CloseableAnimatedImage。AnimatedImageFactoryImpl是AnimatedImageFactory的具体实现。
			- 1.animatedImage：这里个包的主要实现是AnimatedImageResult，上一级目录中说的CloseableAnimatedImage中的Gif和Webp解码后的数据就是用这个AnimatedImageResult包装。其内部储存了animated包中的类：整个动画、每帧画面和预览帧。
		- 2.animatedDrawableFactory：这里的AnimatedDrawableFactory用于将AnimatedImageFactory提供的CloseableImage解析成AnimatedDrawable。具体实现是AnimatedDrawableFactoryImpl。
			- 1.animatedDrawable：包对外提供的实现是AnimatedDrawable，该类实现了Drawable，用于将AnimatedDrawableBackend提供的动画数据绘制在该Drawable设置测View上。
			- 2.animatedBackend用于封装CloseableAnimatedImage提供的AnimatedImageResult。包对外提供的实现是AnimatedDrawableBackendImpl和AnimatedDrawableCachingBackendImpl，AnimatedDrawableCachingBackendImpl用于在AnimatedDrawableCachingBackend之上封装一个缓存功能。
			- 3.other：一些辅助AnimatedDrawable的类


## 二、对象池Pool
> 先来讲讲pool包中的对象池，对象池有什么用？当我们使用一个频繁创建和销毁的对象的时候，为了减少创建和销毁对象所带来的消耗，我们可以维持一个该对象的集合，当不使用的时候将对象放回集合中，使用的时候直接获取引用赋予值。一个典型的对象池就是线程池。在Fresco中由于要频繁地对Bitmap进行操作，所以对Bitmap我们可以使用对象池，此外还有byte数组等。

- 1.先来介绍Pool所用到的数据结构：
	- 1.以Bitmap为例要重新使用一个Bitmap，就需要预期的Bitmap与重用的Bitmap使用的内存字节数相同或者重用的大于预期的，只有这样预期的Bitmap才能完整地放入被重用的Bitmap中。所以这里我们用到了SparseArray(稀疏数组)和Bucket(桶，自定义的类，内部使用了LinkedList)。首先SparseArray的下标表示内存的字节数，由于字节数一般跨度比较大所以使用了SparseArray。SparseArray中储存着Bucket，Bucket表示当两个可以被重用的Bitmap字节数相同时，使用LinkedList进行排列储存。下面的图简单的描述了一下这个数据结构。![](http://upload-images.jianshu.io/upload_images/2911038-951b00f5f2f64da2?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
	- 2.上面的这一个Pool是基于Java内存分配，但是我们都知道一个app能使用的内存是有限制的，因为使用new和创建Bitmap的时候使用的内存都是通过dalvik虚拟机在java堆上分配内存的。Android系统设置了一个Java堆的阈值(48M、24M、16M等)当超出之后就会报OOM。而使用jni代码在native heap上面可以申请的内存却是不受限制的(只受整个手机的内存限制)。所以Fresco当然使用了这个方式以提供Byte数组池。具体封装了jni管理的本地内存的类是imagePipline.memory包下的NativeMemoryChunk类。这里的NativeMemoryChunk只替代了1中申请内存的方式，其他方面不变。
- 2.总结：**在Fresco中一般的静态图片的数据使用的是BitmapPool，这里使用的是java堆上的内存。而动态图片类似Gif和Webp，则是使用Native内存**

## 三、AnimatedDrawable
![](http://upload-images.jianshu.io/upload_images/2911038-9e321a631d14a7bc?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
> 上面的图是factoryAndProvider包中类的结构示意图，**一定要结合项目一起观看**。AnimatedDrawable顾名思义就是一个可以显示动画的Drawable。Android的View在设计的时候为了让Drawable能够实现动画，特意实现了Drawable.CallBack接口。这个接口可以让Drwable对View显示的图像进行调度。AnimatedDrawable就是通过这个机制实现动画的。

- 1.如何创建一个AnimatedDarwable，由上面的图可以看出有以下几个步骤：
	- 1.AnimatedFactoryProvider提供一个AnimatedFactoryImpl。
	- 2.AnimatedFactoryImpl提供一个AnimatedDrawableFactoryImpl和AnimatedImageFactoryImpl。
	- 3.AnimatedImageFactoryImpl将EncodedImage通过GifImage和WebpImage转换成一个AnimatedImageResult并用CloseableAnimatedImage包装。
	- 4.AnimatedDrawableFactoryImpl通过传入的CloseableAnimatedImage获取AnimatedImageResult，然后包装成一个AnimatedDrawableCachingBackendImpl。
	- 5.AbstractAnimatedDrawable继承了Drawable然后内部集成了AnimatedDrawableCachingBackendImpl。最后通过Drawable.CallBack接口在View上绘制AnimatedDrawableCachingBackendImpl中提供的每一帧的数据。
	- 6.**以上就是简述AnimatedDrawable创建的全过程，项目中有详细的注释，大家可以跟着上面这几个步骤观看项目源码**
- 2.AnimatedDrawable显示动画的流程：**我在项目中的AbstractAnimatedDrawable类的开头，详细地描述了AnimatedDrawable的两种启动动画的方式，大家可以顺着项目中描述的路线观看**
## 四、项目使用
> 在administrator.myanimated包下有个MainActivity，用来演示png、jpg、静态webp、动态webp、gif这五种图像的展示。大家在使用的时候记得将自己准备的这个几种文件按命名，放入app的缓存文件夹里。