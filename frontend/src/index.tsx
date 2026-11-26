import {createRoot} from 'react-dom/client'
import {BrowserRouter} from "react-router";
import App from './App';
import {applyTheme} from "./utils/theme";
import {loadSession} from "./resources/client";
import './styles/app.css'

applyTheme()
void loadSession()

createRoot(document.getElementById('root')!).render(
  <BrowserRouter>
    <App/>
  </BrowserRouter>
);
