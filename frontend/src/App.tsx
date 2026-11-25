import Modal from "react-modal";
import AppRoutes from "./AppRoutes";
import {HelpProvider} from "./ui/Help";
import {AddBeatmapProvider} from "./beatmaps/AddBeatmap";
import {FindProvider} from "./ui/Find";
import {ToastProvider} from "./ui/Toast";

Modal.setAppElement("#root")

export default function App() {
  return <ToastProvider><HelpProvider><AddBeatmapProvider><FindProvider><AppRoutes/></FindProvider></AddBeatmapProvider></HelpProvider></ToastProvider>
}
