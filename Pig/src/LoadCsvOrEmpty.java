package input;

import java.lang.Integer;
import java.util.Scanner;
import java.util.Map;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.conf.Configuration;
import org.apache.pig.Expression;
import org.apache.pig.piggybank.storage.CSVLoader;
import org.apache.pig.impl.util.UDFContext;
import org.apache.pig.LoadMetadata;
import org.apache.pig.PigException;
import org.apache.pig.PigWarning;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.ResourceSchema;
import org.apache.pig.ResourceStatistics;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.apache.pig.impl.logicalLayer.schema.Schema.FieldSchema;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigSplit;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import com.microsoft.windowsazure.serviceruntime.RoleEnvironment;
import com.microsoft.windowsazure.services.core.storage.CloudStorageAccount;
import com.microsoft.windowsazure.services.core.storage.StorageCredentialsAccountAndKey;
import com.microsoft.windowsazure.services.core.storage.StorageException;
import com.microsoft.windowsazure.services.table.client.CloudTable;
import com.microsoft.windowsazure.services.table.client.TableOperation;
import com.microsoft.windowsazure.services.table.client.TableServiceEntity;
import com.microsoft.windowsazure.services.table.client.TableQuery;
import com.microsoft.windowsazure.services.table.client.TableQuery.QueryComparisons;

import java.io.StringWriter;

class Column {
  public String name;
  public String type;
  public String onWrongType;

  public Column(String name, String type, String onWrongType) {
    this.name = name;
    this.type = type;
    this.onWrongType = onWrongType;
  }

}

public class LoadCsvOrEmpty extends CSVLoader implements LoadMetadata {

  private boolean hasFiles = false;
  private String instanceId;
  private int instanceIndex;
  private int logEntryIndex;
  private String target;
  private String config;
  private String empty;
  private String onWrongColumnCount;
  private ArrayList<Column> columns = new ArrayList<Column>();
  private String logging_storageAccount;
  private String logging_accountKey;
  private String logging_tableName;
  private CloudTable cloudTable;
  private String filename;
  private int rowIndex;
  private int totalSkipped;

  public LoadCsvOrEmpty(String instanceId, String target, String empty, String config) {
    this.instanceId = instanceId;
    this.target = target;
    this.empty = empty;
    this.config = config;
  }

  private void skip(String message) throws IOException {
    if (totalSkipped < 21) {
      log("SKIP", message);
    } else if (totalSkipped == 21) {
      log("SKIP", "More rows were skipped, but will not be logged to keep the logs from being flooded.");
    } else {
      // don't log
    }
    totalSkipped++;
  }

  @Override
  public Tuple getNext() throws IOException {
    if (hasFiles) {
      Tuple t;
      boolean skipped = false;
      do {
        rowIndex++;
        t = super.getNext();
        skipped = false;
        if (t != null) {

          // verify number of columns
          int size = columns.size();
          if (t.size() != size) {
            String size_mismatch = "[" + filename + ", line:" + rowIndex + "]: expected " + size + " columns, but found " + t.size() + ".";
            if (onWrongColumnCount.equals("skip")) {
              skip(size_mismatch);
              skipped = true;
            } else {
              log("FAIL", size_mismatch);
              throw new ExecException(size_mismatch, 2200, PigException.BUG);
            }
          }

          // verify the right types
          for (int i = 0; i < size; i++) {
            if (!skipped) {
              byte type = t.getType(i);
              Object value = t.get(i);
              Column column = columns.get(i);
              switch (column.type.toLowerCase()) {
                case "bool":
                case "boolean":
                  try {
                    t.set(i, DataType.toBoolean(value));
                  } catch (Exception ex) {
                    String typecast_fail = "[" + filename + ", line:" + rowIndex + ", column:" + i + "]: a boolean was expected, but the value was '" + value + "'.";
                    if (column.onWrongType.equals("skip")) {
                      skip(typecast_fail);
                      skipped = true;
                    } else {
                      log("FAIL", typecast_fail);
                      throw new ExecException(typecast_fail, 2201, PigException.BUG);
                    }
                  }
                  break;
                case "int":
                case "integer":
                  try {
                    t.set(i, DataType.toInteger(value));
                  } catch (Exception ex) {
                    String typecast_fail = "[" + filename + ", line:" + rowIndex + ", column:" + i + "]: an integer was expected, but the value was '" + value + "'.";
                    if (column.onWrongType.equals("skip")) {
                      skip(typecast_fail);
                      skipped = true;
                    } else {
                      log("FAIL", typecast_fail);
                      throw new ExecException(typecast_fail, 2201, PigException.BUG);
                    }
                  }
                  break;
                case "number":
                case "double":
                  try {
                    t.set(i, DataType.toDouble(value));
                  } catch (Exception ex) {
                    String typecast_fail = "[" + filename + ", line:" + rowIndex + ", column:" + i + "]: a double was expected, but the value was '" + value + "'.";
                    if (column.onWrongType.equals("skip")) {
                      skip(typecast_fail);
                      skipped = true;
                    } else {
                      log("FAIL", typecast_fail);
                      throw new ExecException(typecast_fail, 2201, PigException.BUG);
                    }
                  }
                  break;
                case "string":
                case "chararray":
                  try {
                    t.set(i, DataType.toString(value));
                  } catch (Exception ex) {
                    String typecast_fail = "[" + filename + ", line:" + rowIndex + ", column:" + i + "]: a string was expected, but the value was '" + value + "'.";
                    if (column.onWrongType.equals("skip")) {
                      skip(typecast_fail);
                      skipped = true;
                    } else {
                      log("FAIL", typecast_fail);
                      throw new ExecException(typecast_fail, 2201, PigException.BUG);
                    }
                  }
                  break;
              }
            }
          }

        }
      } while (skipped);
      if (t == null) {
        log("INFO", "Completed reading from file: " + filename + "; " + (rowIndex - 1) + " row(s) read; " + totalSkipped + " row(s) skipped.");
      }
      return t;

    } else {
      return null;
    }
  }

  @Override
  public void prepareToRead(RecordReader reader, PigSplit split) throws IOException {
    super.prepareToRead(reader, split);
    String new_filename = ((FileSplit)split.getWrappedSplit()).getPath().getName();
    if (hasFiles && !new_filename.equals(filename)) {
      filename = new_filename;
      rowIndex = 0;
      totalSkipped = 0;
      log("INFO", "Started reading from file: " + filename + ".");
    }
  }

  public ResourceStatistics getStatistics(String location, Job job) throws IOException {
    return null;
  }

  public String[] getPartitionKeys(String location, Job job) throws IOException {
    return null;
  }

  public void setPartitionFilter(Expression partitionFilter) throws IOException {
    // nothing to do
  }

  private void readConfig(String location, Job job) throws IOException {
    if (columns.size() < 1) {

      // read the configuration
      try {
        String raw;
        
        // support local and hadoop
        if (config.startsWith("./")) {

          // read from the local file system
          raw = new String(Files.readAllBytes(Paths.get(config)), StandardCharsets.UTF_8);

        } else {

          // read from hadoop
          Configuration conf = job.getConfiguration();
          Path path = new Path(config);
          FileSystem fs = FileSystem.get(path.toUri(), conf);
          FSDataInputStream inputStream = fs.open(path);
          java.util.Scanner scanner = new java.util.Scanner(inputStream).useDelimiter("\\A");
          raw = scanner.hasNext() ? scanner.next() : "";
          fs.close();

        }

        // parse the JSON (need the root before creating the writer)
        JSONParser parser = new JSONParser();
        JSONObject json = (JSONObject) parser.parse(raw);
        JSONObject logging = (JSONObject) json.get("logging");
        if (logging != null) {
          logging_storageAccount = logging.get("storageAccount").toString();
          logging_accountKey = logging.get("accountKey").toString();
          logging_tableName = logging.get("tableName").toString();
        }
        String onWrongColumnCount = (json.get("onWrongColumnCount") != null) ? json.get("onWrongColumnCount").toString() : "fail";
        JSONArray cc = (JSONArray) json.get("columns");
        if (cc != null) {
          for (int i = 0; i < cc.size(); i++) {
            JSONObject c = (JSONObject) cc.get(i);
            String name = c.get("name").toString();
            String type = c.get("type").toString();
            String onWrongType = (c.get("onWrongType") != null) ? c.get("onWrongType").toString() : "fail";
            columns.add(new Column(name, type, onWrongType));
          }
        }

      } catch (Exception ex) {
        throw new ExecException(ex);
      }

    }
  }

  public ResourceSchema getSchema(String location, Job job) throws IOException {

    // read the config
    readConfig(location, job);

    // build the output
    List<FieldSchema> list = new ArrayList<FieldSchema>();
    for (int i = 0; i < columns.size(); i++) {
      Column column = columns.get(i);
      switch (column.type.toLowerCase()) {
        case "bool":
        case "boolean":
          list.add(new FieldSchema(column.name, DataType.BOOLEAN));
          break;
        case "int":
        case "integer":
          list.add(new FieldSchema(column.name, DataType.INTEGER));
          break;
        case "number":
        case "double":
          list.add(new FieldSchema(column.name, DataType.DOUBLE));
          break;
        case "string":
        case "chararray":
          list.add(new FieldSchema(column.name, DataType.CHARARRAY));
          break;
      }
    }
    return new ResourceSchema(new Schema(list));

  }

  private static boolean empty(final String s) {
    return s == null || s.trim().isEmpty();
  }

	private void log(final String level, final String message) throws IOException {
    if (cloudTable != null) {
      try {
        String partitionKey = instanceId;
        String rowKey = String.format("%04d", instanceIndex) + "-" + String.format("%04d", logEntryIndex);
        LogEntity entity = new LogEntity(partitionKey, rowKey, level, message);
        cloudTable.getServiceClient().execute(logging_tableName, TableOperation.insert(entity));
        logEntryIndex++;
      } catch (Exception ex) {
        throw new ExecException(ex); // wrap the exception
      }
    }
	}

  @Override
  public void setLocation(String location, Job job) throws IOException {

    // read the config
    readConfig(location, job);

    // support local and hadoop
    String target_combiner = location.endsWith("/") || target.startsWith("/") ? "" : "/";
    String target_folder = location.replace("file:", "") + target_combiner + target;
    if (target_folder.startsWith("./")) {

      // read from the local file system
      File dir = new File(target_folder);
      if (dir.isDirectory() && dir.list().length > 0) {
        hasFiles = true;
      }

    } else {

      // see if there are files to process
      Configuration conf = job.getConfiguration();
      FileSystem fs = FileSystem.get(conf);
      Path path = new Path(target_folder);
      if (fs.exists(path)) {
        RemoteIterator<LocatedFileStatus> i_fs = fs.listFiles(path, true);
        while (i_fs.hasNext()) {
          LocatedFileStatus status = i_fs.next();
          if (status.isFile() && status.getBlockSize() > 0) {
            hasFiles = true;
          }
        }
      }
      fs.close();

    }

    String nt = "";

    // enable logging to an Azure Table
    UDFContext udfc = UDFContext.getUDFContext();
    if (!udfc.isFrontend() && cloudTable == null && !empty(logging_storageAccount) && !empty(logging_accountKey) && !empty(logging_tableName)) {
      try {

        // create the table
        CloudStorageAccount account = new CloudStorageAccount(new StorageCredentialsAccountAndKey(logging_storageAccount, logging_accountKey), true);
        cloudTable = account.createCloudTableClient().getTableReference(logging_tableName);
				cloudTable.createIfNotExist();

        
        Configuration conf = udfc.getJobConf();
        StringWriter c = new StringWriter();
        Configuration.dumpConfiguration(conf, c);
        //log("CONF", c.toString());
        nt = c.toString();
        throw new ExecException(nt, 2203, PigException.BUG);
        log("CONF", "pig.script.id = " + conf.get("pig.script.id"));

      } catch (Exception ex) {
        throw new ExecException(nt, 2204, PigException.BUG);
        //throw new ExecException(ex);
      }
    }

    // return either the specified location, the original location, or the empty location
    if (hasFiles) {
      log("INFO", target_combiner + target + " found to contain file(s).");
      super.setLocation(target_folder, job);
    } else if (empty(empty)) {
      log("INFO", target_combiner + target + " does not exist or is empty; using " + location + ".");
      super.setLocation(location, job);
    } else {
      String empty_combiner = location.endsWith("/") || empty.startsWith("/") ? "" : "/";
      String empty_folder = location.replace("file:", "") + empty_combiner + empty;
      log("INFO", target_combiner + target + " does not exist or is empty; using " + empty_combiner + empty + ".");
      super.setLocation(empty_folder, job);
    }

  }

}
