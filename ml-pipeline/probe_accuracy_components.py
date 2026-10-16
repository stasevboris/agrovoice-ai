"""CPU-only numerical export checks of remaining component architectures, never deployment weights."""
import json,gc
import torch
from accuracy_study import ROOT,DATA,REPORT,read,save,checksum,atomic_torch,create,export

def main():
    torch.set_num_threads(2);data=read(DATA/'manifest.json');reports=[]
    for kind in ['efficientnet_b0','leaf_mask','teacher_efficientnet_v2_s','masked_mobilenet_v3_large']:
        torch.manual_seed(20261003);net=create(kind).cpu().eval()
        run=ROOT/'ml-pipeline/runtime/probes'/kind;run.mkdir(parents=True,exist_ok=True)
        atomic_torch(run/'best.pt',{'model':net.state_dict(),'seed':20261003,'dataset_sha256':checksum(DATA/'manifest.json')})
        val=[r for r in (data['masks'] if kind=='leaf_mask' else data['rows']) if r['split']==('val_aux' if kind=='leaf_mask' else 'val')]
        export(net,run,kind,val,'cpu')
        record=read(run/'export.json');record.update(note='Random-head architecture compatibility only; zero training epochs, never package into APK')
        save(run/'export.json',record);reports.append(record)
        print('CPU_EXPORT_PROBE '+kind+' passed',flush=True);del net;gc.collect()
    save(REPORT/'component_cpu_probes.json',{'status':'passed','device':'CPU only','graphs':reports})

if __name__=='__main__':main()
