package com.ejlchina.okhttps.internal;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.List;

import com.ejlchina.okhttps.Download;
import com.ejlchina.okhttps.HttpResult.Body;
import com.ejlchina.okhttps.HttpTask;
import com.ejlchina.okhttps.JsonArr;
import com.ejlchina.okhttps.JsonObj;
import com.ejlchina.okhttps.OnCallback;
import com.ejlchina.okhttps.Process;

import okhttp3.MediaType;
import okhttp3.Response;
import okio.Buffer;

public class ResultBody implements Body {
	
	private Response response;
	private TaskExecutor taskExecutor;
	private boolean onIO = false;
	private OnCallback<Process> onProcess;
	private long stepBytes = 0;
	private double stepRate = -1;
	private boolean rangeIgnored = false;
	private HttpTask<?> httpTask;
	private boolean cached = false;
	private byte[] data;

	ResultBody(HttpTask<?> httpTask, Response response, TaskExecutor taskExecutor) {
		this.httpTask = httpTask;
		this.response = response;
		this.taskExecutor = taskExecutor;
	}

	@Override
	public MediaType getContentType() {
		return response.body().contentType();
	}

	@Override
	public long getContentLength() {
		return response.body().contentLength();
	}
	
	@Override
    public Body nextOnIO() {
    	onIO = true;
    	return this;
    }

	@Override
	public Body setOnProcess(OnCallback<Process> onProcess) {
		if (taskExecutor == null) {
			throw new IllegalStateException("没有 taskExecutor， 不可设置下载进度回调！");
		}
		if (cached) {
			throw new IllegalStateException("开启缓存后，不可设置下载进度回调！");
		}
		this.onProcess = onProcess;
		return this;
	}
	
	@Override
	public Body setStepBytes(long stepBytes) {
		this.stepBytes = stepBytes;
		return this;
	}
	
	@Override
	public Body setStepRate(double stepRate) {
		this.stepRate = stepRate;
		return this;
	}

	@Override
	public Body setRangeIgnored() {
		this.rangeIgnored =true;
		return this;
	}
	
	@Override
	public InputStream toByteStream() {
		InputStream input;
		if (cached) {
			input = new ByteArrayInputStream(cacheBytes());
		} else {
			input = response.body().byteStream();
		}
		if (onProcess != null) {
			long rangeStart = getRangeStart();
			long totalBytes = getContentLength();
			if (!rangeIgnored) {
				totalBytes += rangeStart;
			}
			if (stepRate > 0 && stepRate <= 1) {
				stepBytes = (long) (totalBytes * stepRate);
			}
			if (stepBytes <= 0) {
				stepBytes = Process.DEFAULT_STEP_BYTES;
			}
			return new ProcessInputStream(input, onProcess, totalBytes, stepBytes, 
					rangeIgnored ? 0 : rangeStart, taskExecutor.getExecutor(onIO));
		}
		return input;
	}

	@Override
	public byte[] toBytes() {
		if (cached) {
			return cacheBytes();
		}
		return bodyToBytes();
	}

	@Override
	public Reader toCharStream() {
		if (cached || onProcess != null) {
			return new InputStreamReader(toByteStream());
		}
		return response.body().charStream();
	}
	  
	@Override
	public String toString() {
		if (cached || onProcess != null) {
			MediaType contentType = getContentType();
			Charset charset = contentType != null ? contentType.charset(UTF_8) : UTF_8;
			return new String(toBytes(), charset);
		}
		try {
			return response.body().string();
		} catch (IOException e) {
			throw new HttpException("报文体转化字符串出错", e);
		}
	}

	@Override
	public JsonObj toJsonObj() {
		if (taskExecutor == null) {
			throw new IllegalStateException("没有 taskExecutor，不可做 Json 转换！");
		}
		return taskExecutor.getJsonFactoryNotNull().newJsonObj(toString());
	}

	@Override
	public JsonArr toJsonArr() {
		if (taskExecutor == null) {
			throw new IllegalStateException("没有 taskExecutor，不可做 Json 转换！");
		}
		return taskExecutor.getJsonFactoryNotNull().newJsonArr(toString());
	}

	@Override
	public <T> T toBean(Class<T> type) {
		if (taskExecutor == null) {
			throw new IllegalStateException("没有 taskExecutor，不可做 Json 转换！");
		}
		return taskExecutor.getJsonFactoryNotNull().jsonToBean(type, toString());
	}
	
	@Override
	public <T> List<T> toList(Class<T> type) {
		if (taskExecutor == null) {
			throw new IllegalStateException("没有 taskExecutor，不可做 Json 转换！");
		}
		return taskExecutor.getJsonFactoryNotNull().jsonToList(type, toString());
	}
	
	@Override
	public Download toFile(String filePath) {
		return toFile(new File(filePath));
	}

	@Override
	public Download toFile(File file) {
		if (taskExecutor == null) {
			throw new IllegalStateException("没有 taskExecutor， 不可进行下载操作！");
		}
		if (!file.exists()) {
			try {
				File parent = file.getParentFile();
				if (!parent.exists()) {
					parent.mkdirs();
				}
				file.createNewFile();
			} catch (IOException e) {
				response.close();
				throw new HttpException(
						"Cannot create file [" + file.getAbsolutePath() + "]", e);
			}
		}
		return taskExecutor.download(httpTask, file, toByteStream(), 
				getRangeStart());
	}
	
	@Override
	public Download toFolder(String dirPath) {
		String fileName = resolveFileName();
		String filePath = resolveFilePath(dirPath, fileName);
		int index = 0;
		File file = new File(filePath);
		while (file.exists()) {
			String indexFileName = indexFileName(fileName, index++);
			filePath = resolveFilePath(dirPath, indexFileName);
			file = new File(filePath);
		}
		return toFile(file);
	}

	@Override
	public Download toFolder(File dir) {
		if (dir.exists() && !dir.isDirectory()) {
			response.close();
			throw new HttpException("文件下载失败：文件[" + dir.getAbsolutePath() + "]已存在，并且不是一个目录！");
		}
		if (!dir.exists()) {
			dir.mkdirs();
		}
		return toFolder(dir.getAbsolutePath());
	}
	
	@Override
	public Body cache() {
		if (onProcess != null) {
			throw new IllegalStateException("设置了下载进度回调，不可再开启缓存！");
		}
		cached = true;
		return this;
	}
	
	@Override
	public Body close() {
		response.close();
		data = null;
		return this;
	}
	
	private byte[] cacheBytes() {
		if (data == null) {
			data = bodyToBytes();
		}
		return data;
	}
	
	private byte[] bodyToBytes() {
		if (onProcess != null) {
			Buffer buffer = new Buffer();
			try {
				return buffer.readFrom(toByteStream()).readByteArray();
			} catch (IOException e) {
				throw new HttpException("报文体转化字节数组出错", e);
			} finally {
				response.close();
				buffer.close();
			}
		}
		try {
			return response.body().bytes();
		} catch (IOException e) {
			throw new HttpException("报文体转化字节数组出错", e);
		}
	}
	
	private long getRangeStart() {
		long rangeStart = 0;
		if (response.code() != HttpURLConnection.HTTP_PARTIAL) {
			return rangeStart;
		}
		String range = response.header("Content-Range");
		if (range != null && range.startsWith("bytes")) {
			int index = range.indexOf('-');
			if (index > 5) {
				String start = range.substring(5, index).trim();
				try {
					rangeStart = Long.parseLong(start);
				} catch (Exception ignore) {}
			}
		}
		return rangeStart;
	}
	
	private String resolveFilePath(String dirPath, String fileName) {
		if (dirPath.endsWith("\\") || dirPath.endsWith("/")) {
			return dirPath + fileName;
		}
		return dirPath + "\\" + fileName;
	}

	private String indexFileName(String fileName, int index) {
		int i = fileName.lastIndexOf('.');
		if (i < 0) {
			return fileName + "(" + index + ")";
		}
		String ext = fileName.substring(i);
		if (i > 0) {
			String name = fileName.substring(0, i);
			return name + "(" + index + ")" + ext;
		}
		return "(" + index + ")" + ext;
	}
	
	private String resolveFileName() {
		String fileName = response.header("Content-Disposition");
        // 通过Content-Disposition获取文件名，这点跟服务器有关，需要灵活变通
        if (fileName == null || fileName.length() < 1) {
        	fileName = response.request().url().encodedPath();
            fileName = fileName.substring(fileName.lastIndexOf("/") + 1);
        } else {
            try {
				fileName = URLDecoder.decode(fileName.substring(
				    fileName.indexOf("filename=") + 9), "UTF-8");
			} catch (UnsupportedEncodingException e) {
				throw new HttpException("解码文件名失败", e);
			}
            // 有些文件名会被包含在""里面，所以要去掉，不然无法读取文件后缀
            fileName = fileName.replaceAll("\"", "");
        }
        return fileName;
	}

}
