package rackspace.drg;
 
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Vector;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.apache.commons.io.filefilter.SuffixFileFilter;

import com.google.common.collect.Iterators;
import com.thoughtworks.paranamer.AdaptiveParanamer;
import com.thoughtworks.paranamer.AnnotationParanamer;
import com.thoughtworks.paranamer.BytecodeReadingParanamer;
import com.thoughtworks.paranamer.DefaultParanamer;
import com.thoughtworks.paranamer.JavadocParanamer;
import com.thoughtworks.paranamer.ParameterNamesNotFoundException;
import com.thoughtworks.paranamer.Paranamer;
 
public class JarList {
 
   /**
    * @param args
    */
  
   private File dir;
   private File libs;
   private Pattern pathFilter, classFilter, methodFilterInclude, methodFilterExclude = null;
   // group methods into sets of patterns they match. for example, all update methods
   private HashMap<Pattern, Vector<String>> methodGroups=null;
   private URL[] environment = null; 
 
   public static void main(String[] args) throws IOException, ClassNotFoundException {
     
      JarList jarlist = new JarList(args);
      
      Collection<File> environmentJars = jarlist.listEnvironmentJars();
      jarlist.prepareEnvironment(environmentJars);
      
      for(File jar : jarlist.listJarsOnPath())
      {
         jarlist.processJar(jar);
      }
     
      jarlist.printOutGroups();      
   }
  
   private void prepareEnvironment(Collection<File> jars) throws MalformedURLException 
   {
	   LinkedList<URL> tempList = new LinkedList<URL>();
	   for(File jar : jars)
	   {   
			tempList.add(jar.toURI().toURL());
	   }
	   for(File jar : FileUtils.listFiles(libs,new SuffixFileFilter(".jar"),DirectoryFileFilter.DIRECTORY))
	   {
		   tempList.add(jar.toURI().toURL());
	   }
	   
	   environment = tempList.toArray(new URL[tempList.size()]);
   }

   @SuppressWarnings("unchecked")
   private JarList(String[] args) throws IOException
   {
   // parse command line
      OptionParser parser = new OptionParser();
      parser.accepts("dir").withRequiredArg().describedAs("Directory to analyze").required();
      parser.accepts("libs").withRequiredArg().describedAs("Optional dir with jars (such as a maven repo)");
      parser.accepts("path-filter").withRequiredArg().describedAs("Regex to apply to file paths");
      parser.accepts("class-filter").withRequiredArg().describedAs("Regex to apply to class names");
      parser.accepts("method-filter-include").withRequiredArg().describedAs("Regex to apply to method names");
      parser.accepts("method-filter-exclude").withRequiredArg().describedAs("Regex to apply to method names");
      parser.accepts("method-group").withRequiredArg().describedAs("Regex for grouping method names. Required. Supply .* for all methods in a single group. Supply --method-group multiple times for multiple groups").required();
      OptionSet options = null;
           
      try{
         options = parser.parse(args);
      }
      catch(OptionException e)
      {
         parser.printHelpOn(System.out);
         System.exit(1);
      }
 
      dir = new File( (String) options.valueOf("dir") );
      if(!dir.exists())
      {
         System.err.println("Specified dir does not exist");
         System.exit(1);
      }
      if (options.has("libs"))
    	  libs = new File( (String) options.valueOf("libs") );
     
      if (options.has("path-filter"))
         pathFilter = Pattern.compile( (String) options.valueOf("path-filter") );
      if (options.has("class-filter"))
         classFilter = Pattern.compile( (String) options.valueOf("class-filter") );
      if (options.has("method-filter-include"))
          methodFilterInclude = Pattern.compile( (String) options.valueOf("method-filter-include") );
      if (options.has("method-filter-exclude"))
          methodFilterExclude = Pattern.compile( (String) options.valueOf("method-filter-exclude") );
            
      methodGroups = new HashMap<Pattern, Vector<String>>();
      methodGroups.put(constructors, new Vector<String>());
      for( String methodGroup : (Iterable<String>)options.valuesOf("method-group"))
      {
    	  methodGroups.put(Pattern.compile(methodGroup), new Vector<String>());
      }
      
   }
   
   private Collection<File> listEnvironmentJars()
   {
      return FileUtils.listFiles(
            dir,
            new SuffixFileFilter(".jar"), // apply path filter
            DirectoryFileFilter.DIRECTORY
          );
   }
  
   private Collection<File> listJarsOnPath()
   {
      return FileUtils.listFiles(
            dir,
            new RegexFileFilter(pathFilter), // apply path filter
            DirectoryFileFilter.DIRECTORY
          );
   }
  
   private void processJar(File jar)
   { 
      if(!jar.getName().endsWith(".jar"))
      {
         System.out.println("Not a jar file: " + jar.getName());
      }
      try( JarFile jarFile = new JarFile(jar) ){
         Enumeration<JarEntry> allEntries = jarFile.entries();
         while (allEntries.hasMoreElements()) {
            JarEntry entry = (JarEntry) allEntries.nextElement();
            String className = entry.getName();
            if (className.endsWith(".class") && !className.contains("$")
                  && (!(classFilter!=null) || classFilter.matcher(className).matches())) {
              
               className = className.replaceAll("/", "\\.");
               className = className.substring(0, className.length() - 6);
                 
               try(URLClassLoader ucl = new URLClassLoader(environment)){                 
                  @SuppressWarnings("rawtypes")
                  Class loadedClass = (Class) ucl.loadClass(className);
                  System.out.println("Processing " + jar.getName() + ":\t" + className); // todo log
                  processClass(loadedClass);     
               } catch (ClassNotFoundException e) {                 
                  e.printStackTrace();
                  System.out.println("Skipping class...");
               }
            }
         }
      } catch (IOException e) {        
         e.printStackTrace();
         System.out.println(jar.getAbsolutePath() + ": Error, skipping file...");
      }
   }
  
   private void processAnnotations(StringBuilder sb, Annotation[] annotations)
   {
      for(Annotation annotation : annotations)
      {
         sb.append(annotation.toString()).append(" ");
      }
   }
  
   @SuppressWarnings("rawtypes")
   private void processExceptions(StringBuilder sb, Class[] exceptions)
   {
	   if(exceptions.length==0)return;
      sb.append(" throws");
      for(Class exception : exceptions)
      {
         sb.append(" ").append(exception.getName()).append(",");
      }
      if (sb.charAt(sb.length() - 1) == ',') // remove trailing ","
         sb.setLength(sb.length() - 1);
   }
  
   private void processParams(StringBuilder sb, @SuppressWarnings("rawtypes") Class[] parameterTypes, String[] parameterNames)
   {     
      Iterator<String> names = null;
      if(parameterNames!=null)names = Iterators.forArray(parameterNames);
      for(@SuppressWarnings("rawtypes") Class type : parameterTypes)
      { 
         sb.append( type.getSimpleName() ).append(" ");
         if(names!=null)sb.append(names.next());
         else sb.append(" <noname>");
         sb.append(", ");
      }
      if (sb.charAt(sb.length() - 1) == ' ') // remove trailing ","
         sb.setLength(sb.length() - 2);
   }
   
   
   private static Pattern constructors = Pattern.compile("constructors");
   @SuppressWarnings("rawtypes")
   private void processClass(Class loadedClass) {
      Paranamer paranamer = new AdaptiveParanamer(new DefaultParanamer(), new BytecodeReadingParanamer(), new AnnotationParanamer());
      StringBuilder methodSignature = new StringBuilder();      
      
      for (Constructor constructor : loadedClass.getConstructors()) {
         methodSignature.append("constructor ");
         processAnnotations(methodSignature, constructor.getAnnotations());
        
         methodSignature.append(loadedClass.getCanonicalName()).append("("); // Test(
        
         String[] paranames = null;        
         try{ paranames = paranamer.lookupParameterNames(constructor); }
         catch(ParameterNamesNotFoundException e){}
         processParams(methodSignature, constructor.getParameterTypes(), paranames);
        
         methodSignature.append(")");
         processExceptions(methodSignature, constructor.getExceptionTypes());
         methodSignature.append(";");
         String methodSignatureStr = methodSignature.toString();
         
         if(methodFilterExclude!=null && methodFilterExclude.matcher(methodSignatureStr).matches())continue;
         if(methodFilterInclude==null || methodFilterInclude.matcher(methodSignatureStr).matches())
         methodGroups.get(constructors).add(methodSignatureStr);
         
         methodSignature.setLength(0);
      }
  
      for(Method method : loadedClass.getMethods())
      {
         methodSignature.append(method.getReturnType().getName()).append(" ");        
         processAnnotations(methodSignature, method.getAnnotations());        
         methodSignature.append(loadedClass.getCanonicalName()).append("->").append(method.getName()).append("("); // dostuff(
        
         String[] paranames = null;        
         try{ paranames = paranamer.lookupParameterNames(method); }
         catch(ParameterNamesNotFoundException e){}        
         processParams(methodSignature, method.getParameterTypes(), paranames);
        
         methodSignature.append(")");
         processExceptions(methodSignature, method.getExceptionTypes());
         methodSignature.append(";");
         String methodSignatureStr = methodSignature.toString();
        
         if(methodFilterExclude!=null && methodFilterExclude.matcher(methodSignatureStr).matches())continue;
         if(methodFilterInclude==null || methodFilterInclude.matcher(methodSignatureStr).matches())
         for(Pattern p : methodGroups.keySet())
         {
            if(p.matcher(methodSignatureStr).matches())
            {
               methodGroups.get(p).add(methodSignatureStr);
            }
         }
         methodSignature.setLength(0);
      }      
   }
   
   private class PatternComparator implements Comparator<Pattern>{	   
	    @Override
	    public int compare(Pattern o1, Pattern o2) {
	        return o1.pattern().compareTo(o2.pattern());
	    }
	}
  
   private void printOutGroups()
   {  
	  ArrayList<Pattern> sortedPatterns = new ArrayList<Pattern>();
	  sortedPatterns.addAll(methodGroups.keySet());
      Collections.sort(sortedPatterns, new PatternComparator());
     
      for(Pattern pattern : sortedPatterns)
      { 
         System.out.println("--- " + pattern.pattern() + " ---");        
         String[] sortedMethodSignatures = methodGroups.get(pattern).toArray(new String[methodGroups.get(pattern).size()]);
         Arrays.sort(sortedMethodSignatures);
        
         for(String methodSignature : sortedMethodSignatures)
         {
            System.out.println(methodSignature);
         }
      }
   }
}