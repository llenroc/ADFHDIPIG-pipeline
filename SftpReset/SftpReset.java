package com.plasne;

import java.io.IOException;
import java.util.*;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.io.*;
import org.apache.hadoop.util.*;
import org.apache.hadoop.mapred.*;
import com.jcraft.jsch.*;
import java.time.*;
import java.time.format.*;

public class SftpReset extends Configured implements Tool {

    public static class Map extends MapReduceBase implements Mapper<LongWritable, Text, Text, IntWritable> {

        public void map(LongWritable key, Text value, OutputCollector<Text, IntWritable> collector, Reporter reporter) throws IOException {

            // get parameters from the input file
            String line = value.toString();
            String[] keyval = line.split("=");
            switch (keyval[0]) {
                case "input":
                    String input = keyval[1];
                    job.set("com.plasne.SftpReset.input", input);
                    break;
                case "output":
                    String output = keyval[1];
                    job.set("com.plasne.SftpReset.output", output);
                    break;
                case "hostname":
                    String hostname = keyval[1];
                    job.set("com.plasne.SftpReset.hostname", hostname);
                    break;
                case "username":
                    String username = keyval[1];
                    job.set("com.plasne.SftpReset.username", username);
                    break;
                case "password":
                    String password = keyval[1];
                    job.set("com.plasne.SftpReset.password", password);
                    break;
            }

        }

    }

    public static class Reduce extends MapReduceBase implements Reducer<Text, IntWritable, Text, IntWritable> {

        // defaults
        private int offset = 0;                             // offset time
        private int roundTo = 0;                            // round to next x minutes
        private String input = "";                          // input folder
        private String output = "";                         // output folder
        private String hostname = "";                       // hostname for SFTP server
        private String username = "";                       // username for SFTP server
        private String password = "";                       // password for SFTP server

        public void configure(JobConf job) {
            offset = job.getInt("com.plasne.SftpReset.offset", 0);
            roundTo = job.getInt("com.plasne.SftpReset.roundTo", -1);
            input = job.get("com.plasne.SftpReset.input", "");
            output = job.get("com.plasne.SftpReset.output", "");
            hostname = job.get("com.plasne.SftpReset.hostname", "");
            username = job.get("com.plasne.SftpReset.username", "");
            password = job.get("com.plasne.SftpReset.password", "");
        }

        public void reduce(Text key, Iterator<IntWritable> values, OutputCollector<Text, IntWritable> collector, Reporter reporter) throws IOException {
            
            // determine if there is enough to execute
            Boolean execute = (
                input != null && !input.isEmpty() && 
                output != null && !output.isEmpty() && 
                hostname != null && !hostname.isEmpty() && 
                username != null && !username.isEmpty() && 
                password != null && !password.isEmpty()
            );

            System.out.println ( "execute? " + execute );

            // execute if there are enough properties
            if (execute) {
                JSch jsch = new JSch();
                Session session = null;
                try {

                    // connect via SSH
                    session = jsch.getSession(username, hostname, 22);
                    session.setConfig("StrictHostKeyChecking", "no");
                    session.setPassword(password);
                    session.connect();

                    // connect via SFTP
                    Channel channel = session.openChannel("sftp");
                    channel.connect();
                    ChannelSftp sftpChannel = (ChannelSftp) channel;

                    // offset + round
                    LocalDateTime dt_offset = LocalDateTime.now(Clock.systemUTC()).plusMinutes(offset).withSecond(0).withNano(0);
                    LocalDateTime dt_rounded = dt_offset;
                    if (roundTo != 0) {
                        dt_rounded = dt_rounded.plusMinutes( (60 + roundTo - dt_offset.getMinute()) % roundTo);
                    }
                    if (roundTo < 0) {
                        dt_rounded = dt_rounded.plusMinutes( roundTo );
                    }

                    // rename folder
                    String output_ts = dt_rounded.format(DateTimeFormatter.ofPattern(output));
                    sftpChannel.rename(input, output_ts);

                    // create new folder
                    sftpChannel.mkdir(input);

                    // logout
                    sftpChannel.exit();

                } catch (Exception e) {
                    e.printStackTrace();
                    throw new IOException(e);
                } finally {
                    if (session != null) session.disconnect();
                }
            }

        }

    }

    public int run(String args[]) throws Exception {

        // start new configuration
        JobConf job = new JobConf(getConf()); //, SftpReset.class);

        // read the arguments
        int offset = 0;
        int roundTo = -1;
        String input = "";
        String output = "";
        Boolean roundToWasSet = false;
        Boolean local = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch(arg) {
                case "--offset":
                    offset = Integer.parseInt( args[i + 1] );
                    job.setInt("com.plasne.SftpReset.offset", offset);
                    break;
                case "-r":
                case "--roundTo":
                    roundTo = Integer.parseInt( args[i + 1] );
                    job.setInt("com.plasne.SftpReset.roundTo", roundTo);
                    roundToWasSet = true;
                    break;
                case "-i":
                case "--input":
                    input = args[i + 1];
                    break;
                case "-o":
                case "--output":
                    output = args[i + 1];
                    break;
                case "--debug":
                case "--local":
                    local = true;
                    break;
            }
        }

        // offset + round for output folder
        if (!roundToWasSet) {
            throw new Exception("-r or --roundTo must be set.");
        }
        LocalDateTime dt_offset = LocalDateTime.now(Clock.systemUTC()).plusMinutes(offset).withSecond(0).withNano(0);
        LocalDateTime dt_rounded = dt_offset;
        if (roundTo != 0) {
            dt_rounded = dt_rounded.plusMinutes( (60 + roundTo - dt_offset.getMinute()) % roundTo);
        }
        if (roundTo < 0) {
            dt_rounded = dt_rounded.plusMinutes( roundTo );
        }
        String output_ts = dt_rounded.format(DateTimeFormatter.ofPattern(output));

        // define the job
        job.setJobName("sftpreset");
        if (local) {
            job.setJar("SftpReset.jar");
        } else {
            job.setJarByClass(SftpReset.class);
        }
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(IntWritable.class);
        job.setMapperClass(SftpReset.Map.class);
        job.setCombinerClass(SftpReset.Reduce.class);
        job.setReducerClass(SftpReset.Reduce.class);
        FileInputFormat.setInputPaths(job, new Path(input));
        FileOutputFormat.setOutputPath(job, new Path(output_ts));

        // ensure 1 map and no reduce
        job.set("mapreduce.input.fileinputformat.split.minsize", 256 * 1024 * 1024); // 256 MB
        job.set("mapreduce.input.fileinputformat.split.maxsize", 512 * 1024 * 1024); // 512 MB
        job.setNumReduceTasks(0);
        
        // start the job
        JobClient.runJob(job);
        return 0;
    }

    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(new Configuration(), new SftpReset(), args);
    }

}
