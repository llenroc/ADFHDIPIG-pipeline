package input;

import java.io.IOException;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.conf.Configuration;
import org.apache.pig.piggybank.storage.CSVLoader;
import org.apache.pig.impl.util.UDFContext;
import org.apache.pig.PigWarning;
import org.apache.pig.data.Tuple;

public class LoadCsvOrEmpty extends CSVLoader {

  boolean hasFiles = false;

  public LoadCsvOrEmpty() {
  }

  @Override
  public Tuple getNext() throws IOException {
    //if (hasFiles) {
    //  return super.getNext();
    //} else {
      return null;
    //}
  }

  @Override
  public void setLocation(String location, Job job) throws IOException {
    UDFContext udfc = UDFContext.getUDFContext();
    if (!udfc.isFrontend()) { // only read on backend
      String folder = location.replace("file:", "");

      // support local and hadoop
      if (folder.startsWith("./")) {

        // read from the local file system
        //raw = new String(Files.readAllBytes(Paths.get(config)), StandardCharsets.UTF_8);

      } else {

        // see if there are files to process
        Configuration conf = udfc.getJobConf();
        FileSystem fs = FileSystem.get(conf);
        Path path = new Path(folder);
        //if (fs.exists(path)) {
        //  RemoteIterator<LocatedFileStatus> i_fs = fs.listFiles(path, true);
        //  while (i_fs.hasNext()) {
        //   LocatedFileStatus status = i_fs.next();
        //    if (status.isFile() && status.getBlockSize() > 0) {
        //      hasFiles = true;
        //    }
        //  }
        //}
        fs.close();

      }
    }

    super.setLocation(location, job);
  }

}
