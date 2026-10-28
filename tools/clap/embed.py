import numpy as np, onnxruntime as ort, json, base64, sys
from transformers import AutoTokenizer
sys.path.insert(0,'.')
from prompts import THEMES, ALBUM_NAMES
tok=AutoTokenizer.from_pretrained('..')
txt=ort.InferenceSession('../onnx/text_model.onnx')
def emb(texts):
    out=[]
    for t in texts:
        ids=tok(t,return_tensors='np')['input_ids'].astype(np.int64)
        e=txt.run(None,{'input_ids':ids})[0][0]
        out.append(e/np.linalg.norm(e))
    return np.array(out)
data={"dim":512,"themes":[],"albums":[]}
vecs={}
for k,(label,ps) in THEMES.items():
    e=emb(ps).mean(0); e/=np.linalg.norm(e); vecs['t:'+k]=e
    data["themes"].append({"id":k,"label":label,"vec":base64.b64encode(e.astype('<f4').tobytes()).decode()})
for label,p in ALBUM_NAMES:
    e=emb([p])[0]; vecs['a:'+label]=e
    data["albums"].append({"label":label,"vec":base64.b64encode(e.astype('<f4').tobytes()).decode()})
json.dump(data,open('clap_text.json','w'))
np.save('vecs.npy',vecs,allow_pickle=True)
print(len(data['themes']),len(data['albums']))
