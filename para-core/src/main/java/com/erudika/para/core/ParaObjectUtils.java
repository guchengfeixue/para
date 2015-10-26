/*
 * Copyright 2013-2015 Erudika. http://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.core;

import com.erudika.para.annotations.Locked;
import com.erudika.para.annotations.Stored;
import com.erudika.para.utils.Config;
import com.erudika.para.utils.Utils;
import static com.erudika.para.utils.Utils.getAllDeclaredFields;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.collections.bidimap.DualHashBidiMap;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Contains methods for object/grid mapping, JSON serialization, class scanning and resolution.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
@SuppressWarnings("unchecked")
public final class ParaObjectUtils {

	private static final Logger logger = LoggerFactory.getLogger(ParaObjectUtils.class);
	// maps plural to singular type definitions
	private static final Map<String, String> coreTypes = new DualHashBidiMap();
	// maps lowercase simple names to class objects
	private static final Map<String, Class<? extends ParaObject>> coreClasses = new DualHashBidiMap();
	private static final CoreClassScanner scanner = new CoreClassScanner();
	private static final ObjectMapper jsonMapper = new ObjectMapper();
	private static final ObjectReader jsonReader;
	private static final ObjectWriter jsonWriter;

	static {
		jsonMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		jsonMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		jsonMapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
		jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);
		jsonMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
		jsonMapper.disable(SerializationFeature.WRITE_NULL_MAP_VALUES);
		jsonReader = jsonMapper.reader();
		jsonWriter = jsonMapper.writer();
	}

	private ParaObjectUtils() { }

		/**
	 * A Jackson {@code ObjectMapper}.
	 *
	 * @return JSON object mapper
	 */
	public static ObjectMapper getJsonMapper() {
		return jsonMapper;
	}

	/**
	 * A Jackson JSON reader.
	 *
	 * @param type the type to read
	 * @return JSON object reader
	 */
	public static ObjectReader getJsonReader(Class<?> type) {
		return jsonReader.forType(type);
	}

	/**
	 * A Jackson JSON writer. Pretty print is on.
	 *
	 * @return JSON object writer
	 */
	public static ObjectWriter getJsonWriter() {
		return jsonWriter;
	}

	/**
	 * A Jackson JSON writer. Pretty print is off.
	 *
	 * @return JSON object writer with indentation disabled
	 */
	public static ObjectWriter getJsonWriterNoIdent() {
		return jsonWriter.without(SerializationFeature.INDENT_OUTPUT);
	}

	/////////////////////////////////////////////
	//	     OBJECT MAPPING & CLASS UTILS
	/////////////////////////////////////////////

	/**
	 * Populates an object with an array of query parameters (dangerous!).
	 * <b>This method might be deprecated in the future.</b>
	 *
	 * @param <P> the object type
	 * @param transObject an object
	 * @param paramMap a query parameters map
	 */
	public static <P extends ParaObject> void populate(P transObject, Map<String, String[]> paramMap) {
		if (transObject == null || paramMap == null || paramMap.isEmpty()) {
			return;
		}
		Class<Locked> locked = (paramMap.containsKey(Config._ID)) ? Locked.class : null;
		Map<String, Object> fields = getAnnotatedFields(transObject, locked);
		Map<String, Object> data = new HashMap<String, Object>();
		// populate an object with converted param values from param map.
		try {
			for (Map.Entry<String, String[]> ks : paramMap.entrySet()) {
				String param = ks.getKey();
				String[] values = ks.getValue();
				String value = (values.length > 1) ? getJsonWriter().writeValueAsString(values) : values[0];
				if (fields.containsKey(param)) {
					data.put(param, value);
				}
			}
			setAnnotatedFields(transObject, data, locked);
		} catch (Exception ex) {
			logger.error(null, ex);
		}
	}

	/**
	 * Returns a map of the core data types.
	 * @return a map of type plural - type singular form
	 */
	public static Map<String, String> getCoreTypes() {
		if (coreTypes.isEmpty()) {
			try {
				for (Class<? extends ParaObject> clazz : ParaObjectUtils.getCoreClassesMap().values()) {
					ParaObject p = clazz.newInstance();
					coreTypes.put(p.getPlural(), p.getType());
				}
			} catch (Exception ex) {
				logger.error(null, ex);
			}
		}
		return Collections.unmodifiableMap(coreTypes);
	}

	/**
	 * Returns a map of all registered types.
	 * @param app the app to search for custom types
	 * @return a map of plural - singular form of type names
	 */
	public static Map<String, String> getAllTypes(App app) {
		Map<String, String> map = new HashMap<String, String>(getCoreTypes());
		if (app != null) {
			map.putAll(app.getDatatypes());
		}
		return map;
	}

	/**
	 * Checks if the type of an object matches its real Class name.
	 *
	 * @param so an object
	 * @return true if the types match
	 */
	public static boolean typesMatch(ParaObject so) {
		return (so == null) ? false : so.getClass().equals(toClass(so.getType()));
	}

	/**
	 * @see #getAnnotatedFields(com.erudika.para.core.ParaObject, java.lang.Class, boolean)
	 * @param <P> the object type
	 * @param pojo the object to convert to a map
	 * @return a map of fields and their values
	 */
	public static <P extends ParaObject> Map<String, Object> getAnnotatedFields(P pojo) {
		return getAnnotatedFields(pojo, null);
	}

	/**
	 * @see #getAnnotatedFields(com.erudika.para.core.ParaObject, java.lang.Class, boolean)
	 * @param <P> the object type
	 * @param pojo the object to convert to a map
	 * @param filter a filter annotation. fields that have it will be skipped
	 * @return a map of fields and their values
	 */
	public static <P extends ParaObject> Map<String, Object> getAnnotatedFields(P pojo,
			Class<? extends Annotation> filter) {
		return getAnnotatedFields(pojo, filter, true);
	}

	/**
	 * Returns a map of annotated fields of a domain object. Only annotated fields are returned. This method forms the
	 * basis of an Object/Grid Mapper. It converts an object to a map of key/value pairs. That map can later be
	 * persisted to a data store.
	 * <br>
	 * If {@code convertNestedToJsonString} is true all field values that are objects (i.e. not primitive types or
	 * wrappers) are converted to a JSON string otherwise they are left as they are and will be serialized as regular
	 * JSON objects later (structure is preserved). Null is considered a primitive type. Transient fields and
	 * serialVersionUID are skipped.
	 *
	 * @param <P> the object type
	 * @param pojo the object to convert to a map
	 * @param filter a filter annotation. fields that have it will be skipped
	 * @param convertNestedToJsonString true if you want to flatten the nested objects to a JSON string.
	 * @return a map of fields and their values
	 */
	public static <P extends ParaObject> Map<String, Object> getAnnotatedFields(P pojo,
			Class<? extends Annotation> filter, boolean convertNestedToJsonString) {
		HashMap<String, Object> map = new HashMap<String, Object>();
		if (pojo == null) {
			return map;
		}
		try {
			List<Field> fields = getAllDeclaredFields(pojo.getClass());
			// filter transient fields and those without annotations
			for (Field field : fields) {
				boolean dontSkip = ((filter == null) ? true : !field.isAnnotationPresent(filter));
				if (field.isAnnotationPresent(Stored.class) && dontSkip) {
					String name = field.getName();
					Object value = PropertyUtils.getProperty(pojo, name);
					if (!Utils.isBasicType(field.getType()) && convertNestedToJsonString) {
						value = getJsonWriterNoIdent().writeValueAsString(value);
					}
					map.put(name, value);
				}
			}
		} catch (Exception ex) {
			logger.error(null, ex);
		}

		return map;
	}

	/**
	 * @see #setAnnotatedFields(com.erudika.para.core.ParaObject, java.util.Map, java.lang.Class)
	 * @param <P> the object type
	 * @param data the map of fields/values
	 * @return the populated object
	 */
	public static <P extends ParaObject> P setAnnotatedFields(Map<String, Object> data) {
		return setAnnotatedFields(null, data, null);
	}

	/**
	 * Converts a map of fields/values to a domain object. Only annotated fields are populated. This method forms the
	 * basis of an Object/Grid Mapper.
	 * <br>
	 * Map values that are JSON objects are converted to their corresponding Java types. Nulls and primitive types are
	 * preserved.
	 *
	 * @param <P> the object type
	 * @param pojo the object to populate with data
	 * @param data the map of fields/values
	 * @param filter a filter annotation. fields that have it will be skipped
	 * @return the populated object
	 */
	public static <P extends ParaObject> P setAnnotatedFields(P pojo, Map<String, Object> data,
			Class<? extends Annotation> filter) {
		if (data == null || data.isEmpty()) {
			return null;
		}
		try {
			if (pojo == null) {
				// try to find a declared class in the core package
				pojo = (P) toClass((String) data.get(Config._TYPE)).getConstructor().newInstance();
			}
			List<Field> fields = getAllDeclaredFields(pojo.getClass());
			Map<String, Object> props = new HashMap<String, Object>(data);
			for (Field field : fields) {
				boolean dontSkip = ((filter == null) ? true : !field.isAnnotationPresent(filter));
				String name = field.getName();
				Object value = data.get(name);
				if (field.isAnnotationPresent(Stored.class) && dontSkip) {
					// try to read a default value from the bean if any
					if (value == null && PropertyUtils.isReadable(pojo, name)) {
						value = PropertyUtils.getProperty(pojo, name);
					}
					// handle complex JSON objects deserialized to Maps, Arrays, etc.
					if (!Utils.isBasicType(field.getType()) && value instanceof String) {
						// in this case the object is a flattened JSON string coming from the DB
						value = getJsonReader(field.getType()).readValue(value.toString());
					}
					field.setAccessible(true);
					BeanUtils.setProperty(pojo, name, value);
				}
				props.remove(name);
			}
			// handle unknown (user-defined) fields
			if (!props.isEmpty() && pojo instanceof Sysprop) {
				for (Map.Entry<String, Object> entry : props.entrySet()) {
					String name = entry.getKey();
					Object value = entry.getValue();
					// handle the case where we have custom user-defined properties
					// which are not defined as Java class fields
					if (!PropertyUtils.isReadable(pojo, name)) {
						if (value == null) {
							((Sysprop) pojo).removeProperty(name);
						} else {
							((Sysprop) pojo).addProperty(name, value);
						}
					}
				}
			}
		} catch (Exception ex) {
			logger.error(null, ex);
			pojo = null;
		}
		return pojo;
	}

	/**
	 * Constructs a new instance of a core object.
	 *
	 * @param <P> the object type
	 * @param type the simple name of a class
	 * @return a new instance of a core class. Defaults to {@link com.erudika.para.core.Sysprop}.
	 * @see #toClass(java.lang.String)
	 */
	public static <P extends ParaObject> P toObject(String type) {
		try {
			return (P) toClass(type).getConstructor().newInstance();
		} catch (Exception ex) {
			logger.error(null, ex);
			return null;
		}
	}

	/**
	 * Converts a class name to a real Class object.
	 *
	 * @param type the simple name of a class
	 * @return the Class object or {@link com.erudika.para.core.Sysprop} if the class was not found.
	 * @see java.lang.Class#forName(java.lang.String)
	 */
	public static Class<? extends ParaObject> toClass(String type) {
		return toClass(type, Sysprop.class);
	}

	/**
	 * Converts a class name to a real {@link com.erudika.para.core.ParaObject} subclass. Defaults to
	 * {@link com.erudika.para.core.Sysprop} if the class was not found in the core package path.
	 *
	 * @param type the simple name of a class
	 * @param defaultClass returns this type if the requested class was not found on the classpath.
	 * @return the Class object. Returns null if defaultClass is null.
	 * @see java.lang.Class#forName(java.lang.String)
	 * @see com.erudika.para.core.Sysprop
	 */
	public static Class<? extends ParaObject> toClass(String type, Class<? extends ParaObject> defaultClass) {
		Class<? extends ParaObject> returnClass = defaultClass;
		if (StringUtils.isBlank(type) || !getCoreClassesMap().containsKey(type)) {
			return returnClass;
		}
		return getCoreClassesMap().get(type);
	}

	/**
	 * Searches through the Para core package and {@code Config.CORE_PACKAGE_NAME} package for {@link ParaObject}
	 * subclasses and adds their names them to the map.
	 *
	 * @return a map of simple class names (lowercase) to class objects
	 */
	public static Map<String, Class<? extends ParaObject>> getCoreClassesMap() {
		if (coreClasses.isEmpty()) {
			try {
				Set<Class<? extends ParaObject>> s = scanner.getComponentClasses(ParaObject.class.getPackage().getName());
				if (!Config.CORE_PACKAGE_NAME.isEmpty()) {
					Set<Class<? extends ParaObject>> s2 = scanner.getComponentClasses(Config.CORE_PACKAGE_NAME);
					s.addAll(s2);
				}

				for (Class<? extends ParaObject> coreClass : s) {
					boolean isAbstract = Modifier.isAbstract(coreClass.getModifiers());
					boolean isInterface = Modifier.isInterface(coreClass.getModifiers());
					boolean isCoreObject = ParaObject.class.isAssignableFrom(coreClass);
					if (isCoreObject && !isAbstract && !isInterface) {
						coreClasses.put(coreClass.getSimpleName().toLowerCase(), coreClass);
					}
				}
				logger.debug("Found {} ParaObject classes: {}", coreClasses.size(), coreClasses);
			} catch (Exception ex) {
				logger.error(null, ex);
			}
		}
		return Collections.unmodifiableMap(coreClasses);
	}

	/**
	 * Helper class that lists all classes contained in a given package.
	 */
	private static class CoreClassScanner extends ClassPathScanningCandidateComponentProvider {

		public CoreClassScanner() {
			super(false);
			addIncludeFilter(new AssignableTypeFilter(ParaObject.class));
		}

		public final Set<Class<? extends ParaObject>> getComponentClasses(String basePackage) {
			basePackage = (basePackage == null) ? "" : basePackage;
			Set<Class<? extends ParaObject>> classes = new HashSet<Class<? extends ParaObject>>();
			for (BeanDefinition candidate : findCandidateComponents(basePackage)) {
				try {
					Class<? extends ParaObject> cls = (Class<? extends ParaObject>) ClassUtils.
							resolveClassName(candidate.getBeanClassName(), ClassUtils.getDefaultClassLoader());
					classes.add(cls);
				} catch (Exception ex) {
					logger.error(null, ex);
				}
			}
			return classes;
		}
	}

	/**
	 * Converts a JSON string to a domain object. If we can't match the JSON to a core object, we fall back to
	 * {@link com.erudika.para.core.Sysprop}.
	 *
	 * @param <P> type of object to convert
	 * @param json the JSON string
	 * @return a core domain object or null if the string was blank
	 */
	public static <P extends ParaObject> P fromJSON(String json) {
		if (StringUtils.isBlank(json)) {
			return null;
		}
		try {
			Map<String, Object> map = getJsonReader(Map.class).readValue(json);
			return setAnnotatedFields(map);
		} catch (Exception e) {
			logger.error(null, e);
		}
		return null;
	}

	/**
	 * Converts a domain object to JSON.
	 *
	 * @param <P> type of object to convert
	 * @param obj a domain object
	 * @return the JSON representation of that object
	 */
	public static <P extends ParaObject> String toJSON(P obj) {
		if (obj == null) {
			return "{}";
		}
		try {
			return getJsonWriter().writeValueAsString(obj);
		} catch (Exception e) {
			logger.error(null, e);
		}
		return "{}";
	}

}
