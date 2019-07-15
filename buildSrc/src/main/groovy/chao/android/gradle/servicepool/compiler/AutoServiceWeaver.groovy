package chao.android.gradle.servicepool.compiler

import chao.android.gradle.servicepool.Logger
import chao.android.gradle.servicepool.hunter.asm.BaseWeaver
import chao.android.gradle.servicepool.hunter.asm.ExtendClassWriter
import chao.java.tools.servicepool.IService
import org.apache.commons.io.IOUtils
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode

import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 *
 * @project: zmjx-sp
 * @description:
 *  @author luqin  qinchao@mochongsoft.com
 * @date 2019-07-09
 */
class AutoServiceWeaver extends BaseWeaver {

    private static final String SERVICE_DESC = "Lchao/java/tools/servicepool/annotation/Service;"

    private static final String SERVICES_DIRECTORY = "META-INF/services/"

    private static final String MANIFEST_MF = "META-INF/MANIFEST.MF"


    private Map<Integer, List<String>> serviceConfigMap = new HashMap<>()

    private List<String> services = new ArrayList<>()

    private Map<String, List<ServiceInfo>> serviceInfoMap = new HashMap<>()

    private AutoServiceExtension extension

    @Override
    protected void weaveJarStarted(int jarId) {
        super.weaveJarStarted(jarId)
    }

    @Override
    byte[] weaveSingleClassToByteArray(int jarId, InputStream inputStream) throws IOException {
        ClassReader classReader = new ClassReader(inputStream)
        if (classReader.interfaces.contains(IService.class.getName())) {
            return IOUtils.toByteArray(inputStream)
        }
        ClassWriter classWriter = new ExtendClassWriter(classLoader, ClassWriter.COMPUTE_MAXS)
        ClassVisitor visitor = classWriter

        ClassNode classNode = new ClassNode()
        classReader.accept(classNode, 0)
        //RetentionPolicy.RUNTIME是可见， 其他为不可见
        if (classNode.invisibleAnnotations != null) {
            for (AnnotationNode node : classNode.invisibleAnnotations) {
                if (SERVICE_DESC == node.desc) {
//                    Logger.log("SERVICE_DESC == node.desc", node.desc, node.values, classNode.name.replaceAll("/", "."))
                    List<String> list = serviceConfigMap.get(jarId)
                    if (list == null) {
                        list = new ArrayList<>()
                        serviceConfigMap.put(jarId, list)
                    }
                    list.add(classNode.name.replaceAll("/", "."))

                    services.add(classNode.name.replaceAll("/", "."))
                    int count = 0
                    if (node.values != null) {
                        count = node.values.size() / 2
                    }

                    ServiceInfo serviceInfo = new ServiceInfo(classNode.name)
                    List<ServiceInfo> infos = serviceInfoMap.get(serviceInfo.getPkgName())
                    if (infos == null) {
                        infos = new ArrayList<>()
                        serviceInfoMap.put(serviceInfo.getPkgName(), infos)
                    }
                    infos.add(serviceInfo)
                    serviceInfo.parse(node.values)

                    Map<String, Object> values = new HashMap<>(count)
                    for (int i = 0; i < values.length; i=i+2) {
                        values.put(node.values[i], node.values[i+1])
                    }
                    visitor = new AutoServiceVisitor(classWriter, values)
                }
            }
        }
        classReader.accept(visitor, ClassReader.EXPAND_FRAMES)
        return classWriter.toByteArray()
    }

    @Override
    boolean isWeavableClass(String fullQualifiedClassName) {
        return super.isWeavableClass(fullQualifiedClassName)
    }

    @Override
    protected void weaveJarFinished(int jarId, ZipFile inputZip, ZipOutputStream outputZip) {
        super.weaveJarFinished(jarId, inputZip, outputZip)
    }

    private static void writeZipEntry(String entryName, byte[] content, ZipOutputStream outputZip) {
        ZipEntry zipEntry = new ZipEntry(entryName)
        CRC32 crc32 = new CRC32()
        crc32.update(content)
        zipEntry.setCrc(crc32.getValue())
        zipEntry.setMethod(ZipEntry.STORED)
        zipEntry.setSize(content.length)
        zipEntry.setCompressedSize(content.length)
        zipEntry.setLastAccessTime(ZERO)
        zipEntry.setLastModifiedTime(ZERO)
        zipEntry.setCreationTime(ZERO)
        outputZip.putNextEntry(zipEntry)
        outputZip.write(content)
        outputZip.closeEntry()
    }

    void transformFinished(File destJar) {

        ZipOutputStream outputZip = new ZipOutputStream(new BufferedOutputStream(
                Files.newOutputStream(destJar.toPath())))

        writeGenerateServiceFactories(outputZip)

        for (String pkgName: serviceInfoMap.keySet()) {
            List<ServiceInfo> infoList = serviceInfoMap.get(pkgName)

            writeGenerateFactories(outputZip, pkgName, infoList)
        }

        outputZip.flush()
        outputZip.close()

    }

    private void writeGenerateServiceFactories(ZipOutputStream outputZip) {
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES)

        classWriter.visit(Opcodes.ASM6, Opcodes.ACC_PUBLIC, Constant.GENERATE_SERVICE_FACTORIES_INSTANCE_ASM_NAME, null, Constant.SERVICE_FACTORIES_ASM_NAME)
        classWriter.visitSource("sp\$\$ServiceFactories.java", null)

        MethodVisitor methodVisitor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        methodVisitor.visitCode()
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Constant.SERVICE_FACTORIES_ASM_NAME, "<init>", "()V", false)

        for (String pkgName: serviceInfoMap.keySet()) {
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
            methodVisitor.visitLdcInsn(pkgName)
            String serviceFactory = Constant.GENERATE_SERVICE_PACKAGE_NAME + pkgName.replaceAll("\\.", "_") + Constant.GENERATE_SERVICE_SUFFIX
            methodVisitor.visitTypeInsn(Opcodes.NEW, serviceFactory)
            methodVisitor.visitInsn(Opcodes.DUP)
            methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, serviceFactory, "<init>", "()V", false)
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Constant.GENERATE_SERVICE_FACTORIES_INSTANCE_ASM_NAME, "addFactory", "(Ljava/lang/String;Lchao/java/tools/servicepool/IServiceFactory;)V", false)
        }
        methodVisitor.visitInsn(Opcodes.RETURN)
        methodVisitor.visitMaxs(4, 1)
        methodVisitor.visitEnd()
        classWriter.visitEnd()

        writeZipEntry(Constant.GENERATE_SERVICE_FACTORIES_INSTANCE_ASM_NAME + Constant.GENERATE_FILE_NAME_SUFFIX, classWriter.toByteArray(), outputZip)
    }

    /**
     * 自动生成方法 xxx_ServiceFactory#createServiceProxy
     * @param classWriter
     * @param infoList
     */
    private static void generateServiceProxy(ClassWriter classWriter, List<ServiceInfo> infoList) {
        MethodVisitor methodVisitor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "createServiceProxy", "(Ljava/lang/Class;)Lchao/java/tools/servicepool/ServiceProxy;", null, null)
        methodVisitor.visitCode()

        Label goEnd = new Label()
        for (ServiceInfo info: infoList) {
            Label li = new Label()
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
            methodVisitor.visitLdcInsn(Type.getType(info.getDescriptor()))
            methodVisitor.visitJumpInsn(Opcodes.IF_ACMPNE, li)
            methodVisitor.visitTypeInsn(Opcodes.NEW, "chao/java/tools/servicepool/ServiceProxy")
            methodVisitor.visitInsn(Opcodes.DUP)
            methodVisitor.visitLdcInsn(Type.getType(info.getDescriptor()))
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
            methodVisitor.visitInsn(info.getPriority() + 3)
            methodVisitor.visitInsn(info.getScope() + 3)
            methodVisitor.visitLdcInsn(info.getTag())

            methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "chao/java/tools/servicepool/ServiceProxy", "<init>", "(Ljava/lang/Class;Lchao/java/tools/servicepool/IServiceFactory;IILjava/lang/String;)V", false)
            methodVisitor.visitInsn(Opcodes.ARETURN)
            methodVisitor.visitLabel(li)
        }
        methodVisitor.visitLabel(goEnd)
        methodVisitor.visitInsn(Opcodes.ACONST_NULL)
        methodVisitor.visitInsn(Opcodes.ARETURN)
        methodVisitor.visitMaxs(7, 3)
        methodVisitor.visitEnd()
    }

    private static void generateCreateInstance(ClassWriter classWriter, List<ServiceInfo> infoList) {
        MethodVisitor methodVisitor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "createInstance", "(Ljava/lang/Class;)Lchao/java/tools/servicepool/IService;", "(Ljava/lang/Class<*>;)Lchao/java/tools/servicepool/IService;", null)
        methodVisitor.visitCode()

        Label goEnd = new Label()
        for (ServiceInfo info: infoList) {
            Label li = new Label()
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
            methodVisitor.visitLdcInsn(Type.getType(info.getDescriptor()))
            methodVisitor.visitJumpInsn(Opcodes.IF_ACMPNE, li)
            methodVisitor.visitTypeInsn(Opcodes.NEW, info.getAsmName())
            methodVisitor.visitInsn(Opcodes.DUP)
            methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, info.getAsmName(), "<init>", "()V", false)
            methodVisitor.visitInsn(Opcodes.ARETURN)
            methodVisitor.visitLabel(li)
        }

        methodVisitor.visitLabel(goEnd)
        methodVisitor.visitInsn(Opcodes.ACONST_NULL)
        methodVisitor.visitInsn(Opcodes.ARETURN)
        methodVisitor.visitMaxs(2, 2)
        methodVisitor.visitEnd()
    }

    /**
     * 自动生成 chao/java/tools/servicepool/gen/(xxx_xxx_xxx)_ServiceFactory.class
     * xxx_xxx_xxx是被@Service注解的类的包名pkgName;
     * 同时通过 {@link #generateServiceProxy}生成serviceProxy(Class)和 通过{@link #generateCreateInstance}
     * 生成createInstance(Class)两个方法
     *
     *     @see #generateServiceProxy
     * @param outputZip zip输出
     * @param pkgName   被@Service注解的类的包名pkgName
     * @param infoList  同pkgName下所有的类信息列表
     */
    private static void writeGenerateFactories(ZipOutputStream outputZip, String pkgName, List<ServiceInfo> infoList) {
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES)

        String className = Constant.GENERATE_SERVICE_PACKAGE_NAME + pkgName.replaceAll("\\.", "_") + Constant.GENERATE_SERVICE_SUFFIX
        classWriter.visit(Opcodes.ASM6, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", Constant.SERVICE_FACTORY_ASM_NAME)

        generateServiceProxy(classWriter, infoList)

        generateCreateInstance(classWriter, infoList)

        classWriter.visitEnd()

        writeZipEntry(className + Constant.GENERATE_FILE_NAME_SUFFIX, classWriter.toByteArray(), outputZip)
    }

    @Override
    void setExtension(Object extension) {
        this.extension = extension
    }

    @Override
    boolean weaverJarExcluded(String jarName) {
        List<String> excludes = extension.excludes()
        for (String exclude: excludes) {
            if (jarName.startsWith(exclude)) {
                return true
            }
        }
        return false
    }

    @Override
    protected ClassVisitor wrapClassWriter(ClassWriter classWriter) {
        return super.wrapClassWriter(classWriter)
    }
}
