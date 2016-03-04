/*
 Copyright (c) 2014 by Contributors

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package ml.dmlc.xgboost4j;

import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Booster for xgboost, similar to the python wrapper xgboost.py
 * but custom obj function and eval function not supported at present.
 *
 * @author hzx
 */
class JavaBoosterImpl implements Booster {
  private static final Log logger = LogFactory.getLog(JavaBoosterImpl.class);

  long handle = 0;

  //load native library
  static {
    try {
      NativeLibLoader.initXgBoost();
    } catch (IOException ex) {
      logger.error("load native library failed.");
      logger.error(ex);
    }
  }

  /**
   * init Booster from dMatrixs
   *
   * @param params   parameters
   * @param dMatrixs DMatrix array
   * @throws XGBoostError native error
   */
  JavaBoosterImpl(Map<String, Object> params, DMatrix[] dMatrixs) throws XGBoostError {
    init(dMatrixs);
    setParam("seed", "0");
    setParams(params);
  }

  /**
   * load model from modelPath
   *
   * @param params    parameters
   * @param modelPath booster modelPath (model generated by booster.saveModel)
   * @throws XGBoostError native error
   */
  JavaBoosterImpl(Map<String, Object> params, String modelPath) throws XGBoostError {
    init(null);
    if (modelPath == null) {
      throw new NullPointerException("modelPath : null");
    }
    loadModel(modelPath);
    setParam("seed", "0");
    setParams(params);
  }


  private void init(DMatrix[] dMatrixs) throws XGBoostError {
    long[] handles = null;
    if (dMatrixs != null) {
      handles = dmatrixsToHandles(dMatrixs);
    }
    long[] out = new long[1];
    JNIErrorHandle.checkCall(XgboostJNI.XGBoosterCreate(handles, out));

    handle = out[0];
  }

  /**
   * set parameter
   *
   * @param key   param name
   * @param value param value
   * @throws XGBoostError native error
   */
  public final void setParam(String key, String value) throws XGBoostError {
    JNIErrorHandle.checkCall(XgboostJNI.XGBoosterSetParam(handle, key, value));
  }

  /**
   * set parameters
   *
   * @param params parameters key-value map
   * @throws XGBoostError native error
   */
  public void setParams(Map<String, Object> params) throws XGBoostError {
    if (params != null) {
      for (Map.Entry<String, Object> entry : params.entrySet()) {
        setParam(entry.getKey(), entry.getValue().toString());
      }
    }
  }


  /**
   * Update (one iteration)
   *
   * @param dtrain training data
   * @param iter   current iteration number
   * @throws XGBoostError native error
   */
  public void update(DMatrix dtrain, int iter) throws XGBoostError {
    JNIErrorHandle.checkCall(XgboostJNI.XGBoosterUpdateOneIter(handle, iter, dtrain.getHandle()));
  }

  /**
   * update with customize obj func
   *
   * @param dtrain training data
   * @param obj    customized objective class
   * @throws XGBoostError native error
   */
  public void update(DMatrix dtrain, IObjective obj) throws XGBoostError {
    float[][] predicts = predict(dtrain, true);
    List<float[]> gradients = obj.getGradient(predicts, dtrain);
    boost(dtrain, gradients.get(0), gradients.get(1));
  }

  /**
   * update with give grad and hess
   *
   * @param dtrain training data
   * @param grad   first order of gradient
   * @param hess   seconde order of gradient
   * @throws XGBoostError native error
   */
  public void boost(DMatrix dtrain, float[] grad, float[] hess) throws XGBoostError {
    if (grad.length != hess.length) {
      throw new AssertionError(String.format("grad/hess length mismatch %s / %s", grad.length,
              hess.length));
    }
    JNIErrorHandle.checkCall(XgboostJNI.XGBoosterBoostOneIter(handle, dtrain.getHandle(), grad,
            hess));
  }

  /**
   * evaluate with given dmatrixs.
   *
   * @param evalMatrixs dmatrixs for evaluation
   * @param evalNames   name for eval dmatrixs, used for check results
   * @param iter        current eval iteration
   * @return eval information
   * @throws XGBoostError native error
   */
  public String evalSet(DMatrix[] evalMatrixs, String[] evalNames, int iter) throws XGBoostError {
    long[] handles = dmatrixsToHandles(evalMatrixs);
    String[] evalInfo = new String[1];
    JNIErrorHandle.checkCall(XgboostJNI.XGBoosterEvalOneIter(handle, iter, handles, evalNames,
            evalInfo));
    return evalInfo[0];
  }

  /**
   * evaluate with given customized Evaluation class
   *
   * @param evalMatrixs evaluation matrix
   * @param evalNames   evaluation names
   * @param eval        custom evaluator
   * @return eval information
   * @throws XGBoostError native error
   */
  public String evalSet(DMatrix[] evalMatrixs, String[] evalNames, IEvaluation eval)
          throws XGBoostError {
    String evalInfo = "";
    for (int i = 0; i < evalNames.length; i++) {
      String evalName = evalNames[i];
      DMatrix evalMat = evalMatrixs[i];
      float evalResult = eval.eval(predict(evalMat), evalMat);
      String evalMetric = eval.getMetric();
      evalInfo += String.format("\t%s-%s:%f", evalName, evalMetric, evalResult);
    }
    return evalInfo;
  }

  /**
   * base function for Predict
   *
   * @param data         data
   * @param outPutMargin output margin
   * @param treeLimit    limit number of trees
   * @param predLeaf     prediction minimum to keep leafs
   * @return predict results
   */
  private synchronized float[][] pred(DMatrix data, boolean outPutMargin, int treeLimit,
                                      boolean predLeaf) throws XGBoostError {
    int optionMask = 0;
    if (outPutMargin) {
      optionMask = 1;
    }
    if (predLeaf) {
      optionMask = 2;
    }
    float[][] rawPredicts = new float[1][];
    JNIErrorHandle.checkCall(XgboostJNI.XGBoosterPredict(handle, data.getHandle(), optionMask,
            treeLimit, rawPredicts));
    int row = (int) data.rowNum();
    int col = rawPredicts[0].length / row;
    float[][] predicts = new float[row][col];
    int r, c;
    for (int i = 0; i < rawPredicts[0].length; i++) {
      r = i / col;
      c = i % col;
      predicts[r][c] = rawPredicts[0][i];
    }
    return predicts;
  }

  /**
   * Predict with data
   *
   * @param data dmatrix storing the input
   * @return predict result
   * @throws XGBoostError native error
   */
  public float[][] predict(DMatrix data) throws XGBoostError {
    return pred(data, false, 0, false);
  }

  /**
   * Predict with data
   *
   * @param data         dmatrix storing the input
   * @param outPutMargin Whether to output the raw untransformed margin value.
   * @return predict result
   * @throws XGBoostError native error
   */
  public float[][] predict(DMatrix data, boolean outPutMargin) throws XGBoostError {
    return pred(data, outPutMargin, 0, false);
  }

  /**
   * Predict with data
   *
   * @param data         dmatrix storing the input
   * @param outPutMargin Whether to output the raw untransformed margin value.
   * @param treeLimit    Limit number of trees in the prediction; defaults to 0 (use all trees).
   * @return predict result
   * @throws XGBoostError native error
   */
  public float[][] predict(DMatrix data, boolean outPutMargin, int treeLimit) throws XGBoostError {
    return pred(data, outPutMargin, treeLimit, false);
  }

  /**
   * Predict with data
   *
   * @param data      dmatrix storing the input
   * @param treeLimit Limit number of trees in the prediction; defaults to 0 (use all trees).
   * @param predLeaf  When this option is on, the output will be a matrix of (nsample, ntrees),
   *                  nsample = data.numRow with each record indicating the predicted leaf index
   *                  of each sample in each tree.
   *                  Note that the leaf index of a tree is unique per tree, so you may find leaf 1
   *                  in both tree 1 and tree 0.
   * @return predict result
   * @throws XGBoostError native error
   */
  public float[][] predict(DMatrix data, int treeLimit, boolean predLeaf) throws XGBoostError {
    return pred(data, false, treeLimit, predLeaf);
  }

  /**
   * save model to modelPath
   *
   * @param modelPath model path
   */
  public void saveModel(String modelPath) throws XGBoostError{
    JNIErrorHandle.checkCall(XgboostJNI.XGBoosterSaveModel(handle, modelPath));
  }

  private void loadModel(String modelPath) {
    XgboostJNI.XGBoosterLoadModel(handle, modelPath);
  }

  /**
   * get the dump of the model as a string array
   *
   * @param withStats Controls whether the split statistics are output.
   * @return dumped model information
   * @throws XGBoostError native error
   */
  private String[] getDumpInfo(boolean withStats) throws XGBoostError {
    int statsFlag = 0;
    if (withStats) {
      statsFlag = 1;
    }
    String[][] modelInfos = new String[1][];
    JNIErrorHandle.checkCall(XgboostJNI.XGBoosterDumpModel(handle, "", statsFlag, modelInfos));
    return modelInfos[0];
  }

  /**
   * get the dump of the model as a string array
   *
   * @param featureMap featureMap file
   * @param withStats  Controls whether the split statistics are output.
   * @return dumped model information
   * @throws XGBoostError native error
   */
  private String[] getDumpInfo(String featureMap, boolean withStats) throws XGBoostError {
    int statsFlag = 0;
    if (withStats) {
      statsFlag = 1;
    }
    String[][] modelInfos = new String[1][];
    JNIErrorHandle.checkCall(XgboostJNI.XGBoosterDumpModel(handle, featureMap, statsFlag,
            modelInfos));
    return modelInfos[0];
  }

  /**
   * Dump model into a text file.
   *
   * @param modelPath file to save dumped model info
   * @param withStats bool
   *                  Controls whether the split statistics are output.
   * @throws FileNotFoundException                file not found
   * @throws UnsupportedEncodingException         unsupported feature
   * @throws IOException                          error with model writing
   * @throws XGBoostError native error
   */
  public void dumpModel(String modelPath, boolean withStats) throws IOException, XGBoostError {
    File tf = new File(modelPath);
    FileOutputStream out = new FileOutputStream(tf);
    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, "UTF-8"));
    String[] modelInfos = getDumpInfo(withStats);

    for (int i = 0; i < modelInfos.length; i++) {
      writer.write("booster [" + i + "]:\n");
      writer.write(modelInfos[i]);
    }

    writer.close();
    out.close();
  }


  /**
   * Dump model into a text file.
   *
   * @param modelPath  file to save dumped model info
   * @param featureMap featureMap file
   * @param withStats  bool
   *                   Controls whether the split statistics are output.
   * @throws FileNotFoundException                exception
   * @throws UnsupportedEncodingException         exception
   * @throws IOException                          exception
   * @throws XGBoostError native error
   */
  public void dumpModel(String modelPath, String featureMap, boolean withStats) throws
          IOException, XGBoostError {
    File tf = new File(modelPath);
    FileOutputStream out = new FileOutputStream(tf);
    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, "UTF-8"));
    String[] modelInfos = getDumpInfo(featureMap, withStats);

    for (int i = 0; i < modelInfos.length; i++) {
      writer.write("booster [" + i + "]:\n");
      writer.write(modelInfos[i]);
    }

    writer.close();
    out.close();
  }


  /**
   * get importance of each feature
   *
   * @return featureMap  key: feature index, value: feature importance score
   * @throws XGBoostError native error
   */
  public Map<String, Integer> getFeatureScore() throws XGBoostError {
    String[] modelInfos = getDumpInfo(false);
    Map<String, Integer> featureScore = new HashMap<String, Integer>();
    for (String tree : modelInfos) {
      for (String node : tree.split("\n")) {
        String[] array = node.split("\\[");
        if (array.length == 1) {
          continue;
        }
        String fid = array[1].split("\\]")[0];
        fid = fid.split("<")[0];
        if (featureScore.containsKey(fid)) {
          featureScore.put(fid, 1 + featureScore.get(fid));
        } else {
          featureScore.put(fid, 1);
        }
      }
    }
    return featureScore;
  }


  /**
   * get importance of each feature
   *
   * @param featureMap file to save dumped model info
   * @return featureMap  key: feature index, value: feature importance score
   * @throws XGBoostError native error
   */
  public Map<String, Integer> getFeatureScore(String featureMap) throws XGBoostError {
    String[] modelInfos = getDumpInfo(featureMap, false);
    Map<String, Integer> featureScore = new HashMap<String, Integer>();
    for (String tree : modelInfos) {
      for (String node : tree.split("\n")) {
        String[] array = node.split("\\[");
        if (array.length == 1) {
          continue;
        }
        String fid = array[1].split("\\]")[0];
        fid = fid.split("<")[0];
        if (featureScore.containsKey(fid)) {
          featureScore.put(fid, 1 + featureScore.get(fid));
        } else {
          featureScore.put(fid, 1);
        }
      }
    }
    return featureScore;
  }

  /**
   * Save the model as byte array representation.
   * Write these bytes to a file will give compatible format with other xgboost bindings.
   *
   * If java natively support HDFS file API, use toByteArray and write the ByteArray,
   *
   * @return the saved byte array.
   * @throws XGBoostError
   */
  public byte[] toByteArray() throws XGBoostError {
    byte[][] bytes = new byte[1][];
    JNIErrorHandle.checkCall(XgboostJNI.XGBoosterGetModelRaw(this.handle, bytes));
    return bytes[0];
  }


  /**
   * Load the booster model from thread-local rabit checkpoint.
   * This is only used in distributed training.
   * @return the stored version number of the checkpoint.
   * @throws XGBoostError
   */
  int loadRabitCheckpoint() throws XGBoostError {
    int[] out = new int[1];
    JNIErrorHandle.checkCall(XgboostJNI.XGBoosterLoadRabitCheckpoint(this.handle, out));
    return out[0];
  }

  /**
   * Save the booster model into thread-local rabit checkpoint.
   * This is only used in distributed training.
   * @throws XGBoostError
   */
  void saveRabitCheckpoint() throws XGBoostError {
    JNIErrorHandle.checkCall(XgboostJNI.XGBoosterSaveRabitCheckpoint(this.handle));
  }

  /**
   * transfer DMatrix array to handle array (used for native functions)
   *
   * @param dmatrixs
   * @return handle array for input dmatrixs
   */
  private static long[] dmatrixsToHandles(DMatrix[] dmatrixs) {
    long[] handles = new long[dmatrixs.length];
    for (int i = 0; i < dmatrixs.length; i++) {
      handles[i] = dmatrixs[i].getHandle();
    }
    return handles;
  }

  // making Booster serializable
  private void writeObject(java.io.ObjectOutputStream out)
          throws IOException {
    try {
      out.writeObject(this.toByteArray());
    } catch (XGBoostError ex) {
      throw new IOException(ex.toString());
    }
  }

  private void readObject(java.io.ObjectInputStream in)
          throws IOException, ClassNotFoundException {
    try {
      this.init(null);
      byte[] bytes = (byte[])in.readObject();
      JNIErrorHandle.checkCall(XgboostJNI.XGBoosterLoadModelFromBuffer(this.handle, bytes));
    } catch (XGBoostError ex) {
      throw new IOException(ex.toString());
    }
  }

  @Override
  protected void finalize() throws Throwable {
    super.finalize();
    dispose();
  }

  public synchronized void dispose() {
    if (handle != 0L) {
      XgboostJNI.XGBoosterFree(handle);
      handle = 0;
    }
  }
}
