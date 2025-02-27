import logo from './logo.svg';
import './App.css';
import {Container, Table, Accordion, Spinner, FormControl, Row, Col, Tab, Tabs, Button, Form, InputGroup, ButtonGroup, ToggleButton, Badge} from 'react-bootstrap';
import AceEditor from "react-ace";
import React, { useState, useEffect, useRef } from 'react';
import axios from "axios";
import { useFetch } from "./hooks";
import Graph from "./Graph";

import Viz from "viz.js";
import * as All from "./mode-recipe";
import "ace-builds/src-noconflict/theme-tomorrow";
import ReactHtmlParser from 'react-html-parser'; 

const server = 'http://localhost:54044';

const spinner = <span>&nbsp;<Spinner
as="span"
animation="border"
size="sm"
role="status"
aria-hidden="true"
style={{}}
/></span>;

const Bg = {
  background: "whitesmoke",
  minHeight: "400px",
  width: "100%",
  overflow: "auto",
  paddingTop: "10px"
}

const SVGBg = {
  float: "left",
  display: "contents",
  overflow: "auto"
}

function App() {
  const [simcondition, setSimCondition] = useState("TRUE");
  const [simstarted, setSimStarted] = useState(false);
  const [simresponse, setSimResponse] = useState([]);
  const [mcresponse, setMCResponse] = useState([]);
  const [bound, setBound] = useState(-1);
  const [vloading, setVLoading] = useState(false);
  const [bloading, setBLoading] = useState(false);
  const [mcloading, setMCLoading] = useState(false);
  const [simloading, setSimLoading] = useState(false);
  const [resetsimloading, setResetSimLoading] = useState(false);
  const [symbolicBuild, setSymbolicBuild] = useState(true);
  const [built, setBuilt] = useState(false);
  const [code, setCode] = useFetch("/example.rcp");
  const [radioValue, setRadioValue] = useState('1');
  const [dot, setDot] = useState([]);


  const [interpreterstarted, setInterpreterStarted] = useState(false);
  const [interpreterresponse, setInterpreterResponse] = useState([]);
  const [interpretertransitions, setInterpreterTransitions] = useState([]);
  const [interpreterloading, setInterpreterLoading] = useState(false);
  const [resetinterpreterloading, setResetInterpreterLoading] = useState(false);
  const [interpreternextindex, setInterpreterNextIndex] = useState(0);
  const [interpreterbadge, setInterpreterBadge] = useState(false);


  const radios = [
    { name: 'explicit', value: '1' },
    { name: 'ic3', value: '2' },
    { name: 'bmc', value: '3' },
  ];

  function simulate(){
    if(built == null){
      alert("Build model first.");
      return;
    }

    setSimLoading(true);

    const params = new URLSearchParams();
    params.append('reset', encodeURIComponent(!simstarted));
    params.append('constraint', encodeURIComponent(simcondition));

    var url;
    axios.get(server + "/simulateNext", { params })
         .then((response) => {
            console.log(JSON.stringify(response.data.svgs));
            var res = response.data;
            console.log("svgs");
            console.log((res.svgs));
            setDot([]);
            setDot(res.svgs.map(x => {
              var svg = new DOMParser().parseFromString(x.svg, "image/svg+xml").getElementsByTagNameNS("http://www.w3.org/2000/svg", "svg").item(0);
              console.log("svg: " + svg);
              return svg;
            }));
            delete res.svgs;
            setSimResponse(simresponse.concat([].concat(res)));
            console.log(simresponse);
            setSimLoading(false);
            setSimStarted(true);
         })
         .catch((err) => {
           alert(err.message);
           setSimLoading(false);
         });
  }

function resetSimulate(){
    setResetSimLoading(true);

    setSimStarted(false);
    setSimResponse([]);

    setResetSimLoading(false);
  }

  function renderStep(x) {
    if (x.depth === 0) { return x.state; }
    var last = interpreterresponse[x.depth - 1];

    var render = {};
    Object.keys(x.state).forEach(agent => {
      
      render[agent] = {};
      Object.keys(x.state[agent]).forEach(k => {
        if (x.state[agent][k] != last.state[agent][k]) {
          render[agent][k] = x.state[agent][k];
        }
      });
      console.log(agent, render[agent]);
      if (Object.keys(render[agent]).length === 0) { delete render[agent]; }
    });
    return render;
  }

  function formatStep(render) {
    // console.log(render);
    return (
      <table>  
      {
        Object.keys(render).sort().map(agent => { //return (<tr><td>{agent}</td></tr>)
          return (<tr key={agent}>
            <td>{agent}:</td>
            <td> {
              Object.keys(render[agent]).sort().map((k, index) => {
              return <span>{index > 0 && ', '}{k === "**state**" ? <em>state</em> : k}: {render[agent][k]}</span>
            })}</td>
          </tr>)})
      }
      </table>
    )
  }
  
  const exportData = () => {
    const jsonString = `data:text/json;chatset=utf-8,${encodeURIComponent(
      JSON.stringify(interpreterresponse)
    )}`;
    const link = document.createElement("a");
    link.href = jsonString;
    link.download = "trace.json";
    link.click();
  };


  function formatTransition(t) {
    return t === undefined? "" : (<table>
      <tr><td><em>Sender: </em></td><td>{t.sender}</td></tr>
      <tr><td><em>Command: </em></td><td>{t.send}</td></tr>
      <tr><td><em>Receivers: </em></td><td>{t.receivers.join(", ")}</td></tr>
    </table>)
  }

  function interpret(){
    if(built == null){
      alert("Build model first.");
      return;
    }

    setInterpreterLoading(true);

    const params = new URLSearchParams();
    params.append('reset', encodeURIComponent(!interpreterstarted));
    params.append('index', interpreternextindex);

    var url;
    axios.get(server + "/interpretNext", { params })
         .then((response) => {
            if(response.data.hasOwnProperty("error")){
              alert(response.data.error);
              setInterpreterLoading(false);
              setInterpreterStarted(false);
              setInterpreterTransitions([]);
            } else{
              var res = response.data;
              setInterpreterTransitions(response.data.transitions);
              setInterpreterNextIndex(0);
              // Handle SVGs
              setDot([]);
              setDot(res.svgs.map(x => {
                var svg = new DOMParser().parseFromString(x.svg, "image/svg+xml").getElementsByTagNameNS("http://www.w3.org/2000/svg", "svg").item(0);
                return svg;
              }));
              delete res.svgs;
              setInterpreterResponse(interpreterresponse.concat([res]));
              setInterpreterLoading(false);
              setInterpreterStarted(true);
            }
          })
         .catch((err) => {
           alert(err.message);
           setInterpreterLoading(false);
         });
  }

function backtrackInterpreter(){
  if (interpreterresponse.length <= 1) {
    resetInterpreter();
  }
  else {
    setInterpreterLoading(true);
    axios.get(server + "/interpretBack", { })
      .then((response) => {
        var res = response.data.state;
        setInterpreterTransitions(response.data.transitions);
        setInterpreterNextIndex(0);
        setInterpreterResponse(interpreterresponse.slice(0, -1));
        setInterpreterLoading(false);
        setDot([]);
        setDot(response.data.svgs.map(x => {
          var svg = new DOMParser().parseFromString(x.svg, "image/svg+xml").getElementsByTagNameNS("http://www.w3.org/2000/svg", "svg").item(0);
          return svg;
        }));
      })
      .catch((err) => {
        alert(err.message);
        setInterpreterLoading(false);
      });
  }
}

function resetInterpreter(){
    setResetInterpreterLoading(true);

    setInterpreterStarted(false);
    setInterpreterResponse([]);
    setInterpreterTransitions([]);
    setInterpreterNextIndex("");
    setDot([]);

    setResetInterpreterLoading(false);
  }


  function modelCheck(){
    if(built == null){
      alert("Build model first.");
      return;
    }
    
    setMCLoading(true);
    
    if(symbolicBuild && radioValue == 1){
      alert("Cannot explicitly model check with abstract model. Build explicit model.");
      setMCLoading(false);
    } else if(!symbolicBuild && radioValue > 1){
      alert("Cannot use ic3 or bmc model checking with explicit model. Build abstract model.");
      setMCLoading(false);
    } else{
      const params = new URLSearchParams();
      if(radioValue == 2){
        params.append('ic3', encodeURIComponent(true));
        if(bound > -1){
          params.append('bound', encodeURIComponent(bound));
        }
      } else if(radioValue == 3){
        params.append('bmc', encodeURIComponent(true));
        params.append('bound', encodeURIComponent(bound));
      }

      axios.get(server + "/modelCheck", { params })
         .then((response) => {
            console.log(response.data);
            setMCResponse(response.data.results);
            if (response.data.trace != undefined) {
              setDot([]);
              setDot(response.data.svgs.map(x => { 
                var svg = new DOMParser().parseFromString(x.svg, "image/svg+xml").getElementsByTagNameNS("http://www.w3.org/2000/svg", "svg").item(0);
                return svg;
              }));
              var trace = response.data.trace;
              setInterpreterTransitions(trace[trace.length-1].transitions);
              setInterpreterResponse(trace);
              alert("Counterexample has been loaded in the Interpreter tab.");
              setInterpreterBadge(true);
              setInterpreterStarted(true);
            }
            setMCLoading(false);
         })
         .catch((err) => {
           alert(err.message);
           setMCLoading(false);
         });
    }
  }

  function visualise(){
    setVLoading(true);
    const params = new URLSearchParams();
    params.append('script', encodeURIComponent(code));
    console.log(server);

    axios.get(server + "/setSystem", { params })
         .then((response) => {
           console.log(response);
          axios.get(server + "/toDOTSVG")
               .then((response2) => {
                console.log(response2.data);
                  setDot(response2.data.map(x => {
                    var svg = new DOMParser().parseFromString(x.svg, "image/svg+xml").getElementsByTagNameNS("http://www.w3.org/2000/svg", "svg").item(0);
                    console.log("svg: " + svg);
                    return svg;
                  }));
                  setVLoading(false);
               })
               .catch((err) => {
                 alert(err.message);
                 setVLoading(false);
               })
         })
         .catch((err) => {
           alert(err.message);
           setVLoading(false);
         });
  }

  function buildModel(){
    setBLoading(true);
    resetInterpreter();
    const params = new URLSearchParams();
    params.append('script', encodeURIComponent(code));
    console.log(server);

    axios.get(server + "/setSystem", { params })
         .then((response) => {
           console.log("system set");
           params.append('symbolic', symbolicBuild);
          axios.get(server + "/buildModel", { params })
               .then((response2) => {
                 if(response2.data.hasOwnProperty("error")){
                   alert(response2.data.error);
                 } else{
                   setBuilt(true);
                   alert("Model built successfully");
                 }
                 setBLoading(false);
               })
               .catch((err) => {
                 console.log(err.message);
                 setBLoading(false);
               })
         })
         .catch((err) => {
           console.log(err.message);
           setBLoading(false);
         });
  }

  function xmlToJSX(input){
    return input.replace("xmlns:xlink", "xmlnsXlink")
                .replace("xlink:title", "xlinkTitle")
                .replace("font-size", "fontSize")
                .replace("font-family", "fontFamily")
                .replace("text-anchor", "textAnchor")
                .replace("viewbox", "viewBox");
  }

  return (
    <div className="App">
      <Container fluid>
        <Row>
          <Col xs={6}>
            <AceEditor style={Bg}
              mode="recipe"
              theme="tomorrow"
              fontSize="15px"
              wrapEnabled
              highlightActiveLine
              focus
              height="400px"
              name="UNIQUE_ID_OF_DIV"
              editorProps={{ $blockScrolling: true }}
              value={code}
              onChange={(v) => setCode(v)}
            />
            <InputGroup>
              <Form.Select aria-label="1"
                onChange={ (e) =>{ setSymbolicBuild(e.target.value === "smt") }}>
                <option value="smt">SMT model (allows for infinite-state verification)</option>
                <option value="bdd">BDD model (only for finite-state verification)</option>
              </Form.Select>
              <Button variant="primary" size="lg" onClick={() => buildModel()} disabled={bloading}>
                Build model
                        { bloading && spinner}
              </Button>
              </InputGroup>
          </Col>
          <Col xs={6}>
            <Tabs defaultActiveKey="/" id="uncontrolled-tab-example" className="mb-3"
              onSelect={(e) => { if (e === "interpreter") setInterpreterBadge(false);} }>
              <Tab eventKey="mc" title="Model Checking">
                <Container fluid>
                  <Row>
                    <Col xs={12}>
                    <InputGroup className="mb-3">
                      
                       <InputGroup.Text id="basic-addon1">Type</InputGroup.Text>
                            <ButtonGroup>
                            {radios.map((radio, idx) => (
                              <ToggleButton
                                key={idx}
                                id={`radio-${idx}`}
                                type="radio"
                                variant={idx % 2 ? 'outline-success' : 'outline-danger'}
                                name="radio"
                                value={radio.value}
                                checked={radioValue === radio.value}
                                onChange={(e) => setRadioValue(e.currentTarget.value)}
                              >
                                {radio.name}
                              </ToggleButton>
                              ))}
                            </ButtonGroup>
                          {(radioValue != 1) &&
                              <FormControl
                                size="xs"
                                placeholder="Enter bound (optional for ic3)."
                                aria-label="bound"
                                aria-describedby="basic-addon1"
                                onChange={(e) => setBound(e.currentTarget.value)}
                              />
                              }
                              <Button variant="primary" size="lg" disabled={mcloading} onClick={modelCheck}>
                                Start
                                { mcloading && spinner}
                              </Button>
                    </InputGroup>

                    </Col>
                  </Row>
                  <Row>
                    <Col>
                      <Accordion defaultActiveKey="0">
                        {mcresponse.map((x, i) => {
                          return <Accordion.Item key={i} eventKey={i}>
                          <Accordion.Header className={x.result == "true" ? "prop-true" : x.result == "false" ? "prop-false" : "prop-unknown"}>{x.spec}</Accordion.Header>
                          <Accordion.Body style={{whiteSpace: "pre-wrap"}}>{x.output}
                            </Accordion.Body>
                          </Accordion.Item>;
                        })}
                      </Accordion>
                    </Col>
                  </Row>
                </Container>
              </Tab>
              {/* <Tab eventKey="sim" title="Simulation">
              <Container fluid>
                  <Row>
                    <Col xs={12}>
                      <InputGroup className="mb-3">
                            {(simstarted || simcondition != "TRUE") &&
                                <FormControl
                                  size="xs"
                                  placeholder="Enter condition on next state (no LTL operators)."
                                  aria-label="sim-condition"
                                  aria-describedby="basic-addon2"
                                  onChange={(e) => setSimCondition(e.currentTarget.value)}
                                />
                            }
                            <Button variant="primary" size="lg" disabled={simloading} onClick={simulate}>
                              {!simstarted && <span>Start</span>}
                              {simstarted && <span>Next</span>}
                              { simloading && spinner}
                            </Button>
                            <Button variant="secondary" size="lg" disabled={resetsimloading} onClick={resetSimulate}>
                              Reset
                              { resetsimloading && spinner}
                            </Button>
                      </InputGroup>
                    </Col>
                  </Row>
                  <Row>
                    <Col style={{height: "345px", overflowY: "auto", overflowX: "auto"}}>
                      <Table striped bordered hover>
                        <thead>
                          <tr>
                            <th>#Step</th>
                            <th>Changed Variables</th>
                          </tr>
                        </thead>
                        <tbody>
                        {simresponse.map((x, i) => {
                          return <tr key={i}>
                          <td>{i}</td>
                          <td>{JSON.stringify(x)}</td>
                        </tr>;
                        })}
                        </tbody>
                      </Table>
                    </Col>
                  </Row>
                </Container>
              </Tab> */}
              <Tab eventKey="interpreter" title={
                <React.Fragment>
                  Interpreter{' '} 
                  {interpreterbadge && <Badge pill bg="primary" show={false}>!</Badge>}
                </React.Fragment>
              }>
              <Container fluid>
                  <Row>
                    <Col xs={12}>
                      <InputGroup className="mb-3">
                            {
                            <Form.Select 
                              aria-label="interpreter-next"
                              value={interpreternextindex}
                              onChange={(e) => {setInterpreterNextIndex(e.target.value)}}>
                              {(!interpreterstarted) && <option value="" disabled selected>Choose a transition here</option>}
                              {interpretertransitions.map((x, i) => {
                                return <option key={i} value={i}>({i}): {x.send} from {x.sender} to [{x.receivers.join(", ")}]</option>
                              })}
                            </Form.Select>
                            }
                            <Button variant="primary" size="lg"
                              disabled={interpreterloading || (interpreterstarted && interpretertransitions.length == 0)}
                              onClick={interpret}>
                              {!interpreterstarted && <span>Start</span>}
                              {interpreterstarted && <span>Next</span>}
                              {interpreterloading && spinner}
                            </Button>
                            <Button variant="secondary" size="lg" disabled={resetinterpreterloading} onClick={resetInterpreter}>
                              Reset
                              { resetinterpreterloading && spinner }
                            </Button>
                            <Button variant="secondary" size="lg" disabled={interpreterloading} onClick={backtrackInterpreter}>
                              Backtrack
                              { interpreterloading && spinner }
                            </Button>
                            <Button variant="secondary" size="lg" disabled={interpreterloading} onClick={exportData}>
                              Export
                              { interpreterloading && spinner }
                            </Button>
                      </InputGroup>
                    </Col>
                  </Row>
                  <Row>
                    <Col style={{height: "345px", overflowY: "auto", overflowX: "auto"}}>
                      <Table striped bordered hover>
                        <thead>
                          <tr>
                            <th>#Step</th>
                            <th>Changed Variables</th>
                          </tr>
                        </thead>
                        <tbody>
                        {interpreterresponse.map((x, i) => {
                          
                          return (
                          <React.Fragment>{
                            x.inboundTransition !== undefined ? 
                            // Transition
                            <tr key={i}>
                            <td></td>
                            <td>{formatTransition(x.inboundTransition)}</td>
                            </tr>
                            :
                            ""
                          }
                            {/* 
                            // State
                            // TODO store the renders somewhere, instead of
                            // recomputing them all the time */}
                            <tr key={i}>
                            <td>{x.depth}</td>
                            <td>{formatStep(renderStep(x))}</td>
                          </tr>
                          </React.Fragment>)
                        })}
                        </tbody>
                      </Table>
                    </Col>
                  </Row>
                </Container>
              </Tab>
            </Tabs>
          </Col>
        </Row>
        <hr />
        <Row>
          <Col xs={12}>
                <Button variant="primary" 
                        size="lg" 
                        onClick={() => visualise()}
                        disabled={vloading}>
                Visualise
                        { vloading && spinner}
                </Button>
            <Container fluid style={Bg}>
              <Row>
                  {dot.map((x, i) => {
                      return <Col style={SVGBg} key={i}><Graph svg={x}/></Col>;
                  })}
              </Row>
            </Container>
          </Col>        
        </Row>
      </Container>
    </div>
  );
}

export default App;

